/*
 * Copyright (C) 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.teleport.v2.templates;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.cloud.teleport.kafka.connector.KafkaIO;
import com.google.cloud.teleport.v2.coders.FailsafeElementCoder;
import com.google.cloud.teleport.v2.transforms.BigQueryConverters.FailsafeJsonToTableRow;
import com.google.cloud.teleport.v2.transforms.ErrorConverters;
import com.google.cloud.teleport.v2.transforms.ErrorConverters.WriteKafkaMessageErrors;
import com.google.cloud.teleport.v2.transforms.JavascriptTextTransformer.FailsafeJavascriptUdf;
import com.google.cloud.teleport.v2.utils.SchemaUtils;
import com.google.cloud.teleport.v2.values.FailsafeElement;
import com.google.common.collect.ImmutableMap;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.NullableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryInsertError;
import org.apache.beam.sdk.io.gcp.bigquery.DynamicDestinations;
import org.apache.beam.sdk.io.gcp.bigquery.InsertRetryPolicy;
import org.apache.beam.sdk.io.gcp.bigquery.TableDestination;
import org.apache.beam.sdk.io.gcp.bigquery.TableRowJsonCoder;
import org.apache.beam.sdk.io.gcp.bigquery.WriteResult;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation.Required;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.ValueInSingleWindow;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MultiKafkaCDCToBigQuery} pipeline is a streaming pipeline which ingests CDC data from Kafka,
 * executes a UDF, and outputs the resulting records to BigQuery. Any errors which occur in the
 * transformation of the data, execution of the UDF, or inserting into the output table will be
 * inserted into a separate errors table in BigQuery. The errors table will be created if it does
 * not exist prior to execution. Both output and error tables are specified by the user as
 * parameters.
 *
 * <p><b>Pipeline Requirements</b>
 *
 * <ul>
 *   <li>The Kafka topic exists and the message is encoded in a valid JSON format.
 *   <li>The BigQuery output table exists.
 *   <li>The Kafka brokers are reachable from the Dataflow worker machines.
 * </ul>
 *
 * <p><b>Example Usage</b>
 *
 * <pre>
 *
 * # Set some environment variables
 * PROJECT=my-project
 * TEMP_BUCKET=my-temp-bucket
 * INPUT_TOPIC_REGEX=input-topic-regex
 * OUTPUT_TABLE_PREFIX=project:dataset.
 * OUTPUT_TABLE_REPLACEMENT=replacement-from-input-topic-regex
 * JS_PATH=my-js-path-on-gcs
 * JS_FUNC_NAME=my-js-func-name
 * BOOTSTRAP=my-comma-separated-bootstrap-servers
 *
 * # Set containerization vars
 * IMAGE_NAME=my-image-name
 * TARGET_GCR_IMAGE=gcr.io/${PROJECT}/${IMAGE_NAME}
 * BASE_CONTAINER_IMAGE=my-base-container-image
 * BASE_CONTAINER_IMAGE_VERSION=my-base-container-image-version
 * APP_ROOT=/path/to/app-root
 * COMMAND_SPEC=/path/to/command-spec
 *
 * # Build and upload image
 * mvn clean package \
 * -Dimage=${TARGET_GCR_IMAGE} \
 * -Dbase-container-image=${BASE_CONTAINER_IMAGE} \
 * -Dbase-container-image.version=${BASE_CONTAINER_IMAGE_VERSION} \
 * -Dapp-root=${APP_ROOT} \
 * -Dcommand-spec=${COMMAND_SPEC}
 *
 * # Create an image spec in GCS that contains the path to the image
 * {
 *    "docker_template_spec": {
 *       "docker_image": $TARGET_GCR_IMAGE
 *     }
 *  }
 *
 * # Execute template:
 * API_ROOT_URL="https://dataflow.googleapis.com"
 * TEMPLATES_LAUNCH_API="${API_ROOT_URL}/v1b3/projects/${PROJECT}/templates:launch"
 * JOB_NAME="multikafka-cdc-to-bigquery`date +%Y%m%d-%H%M%S-%N`"
 *
 * time curl -X POST -H "Content-Type: application/json"     \
 *     -H "Authorization: Bearer $(gcloud auth print-access-token)" \
 *     "${TEMPLATES_LAUNCH_API}"`
 *     `"?validateOnly=false"`
 *     `"&dynamicTemplate.gcsPath=${TEMP_BUCKET}/path/to/image-spec"`
 *     `"&dynamicTemplate.stagingLocation=${TEMP_BUCKET}/staging" \
 *     -d '
 *      {
 *       "jobName":"'$JOB_NAME'",
 *       "parameters": {
 *           "outputTablePrefix":"'$OUTPUT_TABLE_PREFIX'",
 *           "outputTableReplacement":"'$OUTPUT_TABLE_REPLACEMENT'",
 *           "inputTopicRegex":"'$INPUT_TOPIC_REGEX'",
 *           "javascriptTextTransformGcsPath":"'$JS_PATH'",
 *           "javascriptTextTransformFunctionName":"'$JS_FUNC_NAME'",
 *           "bootstrapServers":"'$BOOTSTRAP'"
 *        }
 *       }
 *      '
 * </pre>
 */
public class MultiKafkaCDCToBigQuery {

  /* Logger for class. */
  private static final Logger LOG = LoggerFactory.getLogger(MultiKafkaCDCToBigQuery.class);

  /** The tag for the main output for the UDF. */
  private static final TupleTag<FailsafeElement<KV<String, String>, String>> UDF_OUT =
      new TupleTag<FailsafeElement<KV<String, String>, String>>() {};

  /** The tag for the main output of the json transformation. */
  static final TupleTag<TableRow> TRANSFORM_OUT = new TupleTag<TableRow>() {};

  /** The tag for the dead-letter output of the udf. */
  static final TupleTag<FailsafeElement<KV<String, String>, String>> UDF_DEADLETTER_OUT =
      new TupleTag<FailsafeElement<KV<String, String>, String>>() {};

  /** The tag for the dead-letter output of the json to table row transform. */
  static final TupleTag<FailsafeElement<KV<String, String>, String>>
      TRANSFORM_DEADLETTER_OUT = new TupleTag<FailsafeElement<KV<String, String>, String>>() {};

  /** The default suffix for error tables if dead letter table is not specified. */
  private static final String DEFAULT_DEADLETTER_TABLE_SUFFIX = "_error_records";

  /** String/String Coder for FailsafeElement. */
  private static final FailsafeElementCoder<String, String> FAILSAFE_ELEMENT_CODER =
      FailsafeElementCoder.of(
          NullableCoder.of(StringUtf8Coder.of()), NullableCoder.of(StringUtf8Coder.of()));

  /**
   * The {@link Options} class provides the custom execution options passed by the executor at the
   * command-line.
   */
  public interface Options extends PipelineOptions {

    @Description("Kafka Bootstrap Servers")
    @Required
    String getBootstrapServers();

    void setBootstrapServers(String bootstrapServers);

    @Description("Regular Expression of Kafka topics to read from")
    @Required
    String getInputTopicRegex();

    void setInputTopicRegex(String inputTopicRegex);

    @Description("BigQuery project and dataset name")
    @Required
    String getOutputTablePrefix();

    void setOutputTablePrefix(String outputTablePrefix);

    @Description("Replacement from inputTopicRegex to be applied in BigQuery table name")
    @Required
    String getOutputTableReplacement();

    void setOutputTableReplacement(String outputTableReplacement);

    @Description(
        "The dead-letter table to output to within BigQuery in <project-id>:<dataset>.<table> "
            + "format. If it doesn't exist, it will be created during pipeline execution.")
    String getOutputDeadletterTable();

    void setOutputDeadletterTable(String outputDeadletterTable);

    @Description("Gcs path to javascript udf source")
    String getJavascriptTextTransformGcsPath();

    void setJavascriptTextTransformGcsPath(String javascriptTextTransformGcsPath);

    @Description("UDF Javascript Function Name")
    String getJavascriptTextTransformFunctionName();

    void setJavascriptTextTransformFunctionName(String javascriptTextTransformFunctionName);
  }

  /**
   * The main entry-point for pipeline execution. This method will start the pipeline but will not
   * wait for it's execution to finish. If blocking execution is required, use the {@link
   * MultiKafkaCDCToBigQuery#run(Options)} method to start the pipeline and invoke {@code
   * result.waitUntilFinish()} on the {@link PipelineResult}.
   *
   * @param args The command-line args passed by the executor.
   */
  public static void main(String[] args) {
    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);

    run(options);
  }

  /**
   * Runs the pipeline to completion with the specified options. This method does not wait until the
   * pipeline is finished before returning. Invoke {@code result.waitUntilFinish()} on the result
   * object to block until the pipeline is finished running if blocking programmatic execution is
   * required.
   *
   * @param options The execution options.
   * @return The pipeline result.
   */
  public static PipelineResult run(Options options) {

    // Create the pipeline
    Pipeline pipeline = Pipeline.create(options);

    // Register the coder for pipeline
    FailsafeElementCoder<KV<String, String>, String> coder =
        FailsafeElementCoder.of(
            KvCoder.of(
                NullableCoder.of(StringUtf8Coder.of()), NullableCoder.of(StringUtf8Coder.of())),
            NullableCoder.of(StringUtf8Coder.of()));

    CoderRegistry coderRegistry = pipeline.getCoderRegistry();
    coderRegistry.registerCoderForType(coder.getEncodedTypeDescriptor(), coder);

    /*
     * Steps:
     *  1) Read messages in from Kafka
     *  2) Transform the messages into TableRows
     *     - Transform message payload via UDF
     *     - Convert UDF result to TableRow objects
     *  3) Write successful records out to BigQuery
     *  4) Write failed records out to BigQuery
     */

    PCollectionTuple convertedTableRows =
        pipeline
            /*
             * Step #1: Read messages in from Kafka
             */
            .apply(
                "ReadFromKafka",
                KafkaIO.<String, String>read()
                    .updateConsumerProperties(
                        ImmutableMap.of(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))
                    .withBootstrapServers(options.getBootstrapServers())
                    .withTopicRegex(options.getInputTopicRegex())
                    .withCustomizedKeyRegex(options.getInputTopicRegex())
                    .withCustomizedKeyPrefix(options.getOutputTablePrefix())
                    .withCustomizedKeyReplacement(options.getOutputTableReplacement())
                    .withKeyDeserializerAndCoder(
                        StringDeserializer.class, NullableCoder.of(StringUtf8Coder.of()))
                    .withValueDeserializerAndCoder(
                        StringDeserializer.class, NullableCoder.of(StringUtf8Coder.of()))
                    .withoutMetadata())

            /*
             * Step #2: Transform the Kafka Messages by UDF Javascript Function
             */
            .apply("TransformMessagesUsingUDF", new TransformMessagesUsingUDF(options));

    /*
     * Step #3: Write the successful records out to BigQuery
     */
    convertedTableRows
            .get(UDF_OUT)
            .apply("WritetoBigQuery",
                    BigQueryIO
                            .<FailsafeElement<KV<String, String>, String>>write()
                            .to(new DynamicDestinations<FailsafeElement<KV<String, String>, String>, FailsafeElement<KV<String, String>, String>>() {
                              public FailsafeElement<KV<String, String>, String> getDestination(ValueInSingleWindow<FailsafeElement<KV<String, String>, String>> element) {
                                return element.getValue();
                              }
                              public TableDestination getTable(FailsafeElement<KV<String, String>, String> element) {
                                return new TableDestination(element.getOriginalPayload().getKey(), "Record from Kafka");
                              }
                              public TableSchema getSchema(FailsafeElement<KV<String, String>, String> element) {
                                List<TableFieldSchema> fields = new ArrayList<TableFieldSchema>();
                                JSONObject jsonObj = new JSONObject(element.getOriginalPayload().getValue());
                                JSONObject jsonSchema = jsonObj.getJSONObject("schema");
                                JSONArray jsonFields = jsonSchema.getJSONArray("fields");
                                int jsonFieldsLen = jsonFields.length(), idx;
                                JSONObject jsonField;

                                for(idx = 0; idx < jsonFieldsLen; idx++){
                                  jsonField = jsonFields.getJSONObject(idx);
                                  fields.add(new TableFieldSchema()
                                                .setName(jsonField.getString("field").replaceAll("([^a-zA-Z0-9])", "_"))
                                                .setType((jsonField.getString("type") == "int32" || jsonField.getString("type") == "int64") ? "INTEGER"
                                                        : (jsonField.getString("type") == "double") ? "FLOAT"
                                                        : (jsonField.getString("type") == "boolean") ? "BOOLEAN"
                                                        : "STRING")
                                                .setMode((jsonField.getBoolean("optional") == false)
                                                              ? "REQUIRED"
                                                              : "NULLABLE")
                                  );
                                }

                                return new TableSchema().setFields(fields);
                              }
                            })
                            .withFormatFunction(new SerializableFunction<FailsafeElement<KV<String, String>, String>, TableRow>() {
                              @Override
                              public TableRow apply(FailsafeElement<KV<String, String>, String> element) {
                                String json = element.getPayload();
                                TableRow row = null;
                                // Parse the JSON into a {@link TableRow} object.
                                try (InputStream inputStream =
                                             new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
                                  row = TableRowJsonCoder.of().decode(inputStream, Coder.Context.OUTER);

                                } catch (IOException e) {
                                  throw new RuntimeException("Failed to serialize json to table row: " + json, e);
                                }

                                return row;
                              }
                            })
                            .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
                            .withWriteDisposition(WriteDisposition.WRITE_APPEND)
            );


    return pipeline.run();
  }

   /**
   * The {@link TransformMessagesUsingUDF} class is a {@link PTransform} which transforms incoming Kafka
   * Message objects by applying an optional UDF to the input. The executions of the UDF objects
   * is done in a fail-safe way by wrapping the element with it's original payload inside
   * the {@link FailsafeElement} class. The {@link TransformMessagesUsingUDF} will output
   * a {@link PCollectionTuple} which contains all UDF output and dead-letter {@link PCollection}.
   *
   * <p>The {@link PCollectionTuple} output will contain the following {@link PCollection}:
   *
   * <ul>
   *   <li>{@link TransformMessagesUsingUDF#UDF_OUT} - Contains all {@link FailsafeElement} records
   *       successfully processed by the optional UDF.
   *   <li>{@link TransformMessagesUsingUDF#UDF_DEADLETTER_OUT} - Contains all {@link FailsafeElement} records
   *       which failed processing during the UDF execution.
   * </ul>
   */
  static class TransformMessagesUsingUDF
          extends PTransform<PCollection<KV<String, String>>, PCollectionTuple> {

    private final Options options;

    TransformMessagesUsingUDF(Options options) {
      this.options = options;
    }

    @Override
    public PCollectionTuple expand(PCollection<KV<String, String>> input) {

      PCollectionTuple udfOut =
              input
                      // Map the incoming messages into FailsafeElements so we can recover from failures
                      // across multiple transforms.
                      .apply("MapToRecord", ParDo.of(new MessageToFailsafeElementFn()))
                      .apply(
                              "InvokeUDF",
                              FailsafeJavascriptUdf.<KV<String, String>>newBuilder()
                                      .setFileSystemPath(options.getJavascriptTextTransformGcsPath())
                                      .setFunctionName(options.getJavascriptTextTransformFunctionName())
                                      .setSuccessTag(UDF_OUT)
                                      .setFailureTag(UDF_DEADLETTER_OUT)
                                      .build());

      // Re-wrap the PCollections so we can return a single PCollectionTuple
      return PCollectionTuple.of(UDF_OUT, udfOut.get(UDF_OUT))
              .and(UDF_DEADLETTER_OUT, udfOut.get(UDF_DEADLETTER_OUT));
    }
  }

  /**
   * The {@link MessageToFailsafeElementFn} wraps an Kafka Message with the {@link FailsafeElement}
   * class so errors can be recovered from and the original message can be output to a error records
   * table.
   */
  static class MessageToFailsafeElementFn
      extends DoFn<KV<String, String>, FailsafeElement<KV<String, String>, String>> {

    @ProcessElement
    public void processElement(ProcessContext context) {
      KV<String, String> message = context.element();
      context.output(FailsafeElement.of(message, message.getValue()));
    }
  }

  /**
   * Method to wrap a {@link BigQueryInsertError} into a {@link FailsafeElement}.
   *
   * @param insertError BigQueryInsert error.
   * @return FailsafeElement object.
   */
  protected static FailsafeElement<String, String> wrapBigQueryInsertError(
      BigQueryInsertError insertError) {

    FailsafeElement<String, String> failsafeElement;
    try {

      failsafeElement =
          FailsafeElement.of(
              insertError.getRow().toPrettyString(), insertError.getRow().toPrettyString());
      failsafeElement.setErrorMessage(insertError.getError().toPrettyString());

    } catch (IOException e) {
      LOG.error("Failed to wrap BigQuery insert error.");
      throw new RuntimeException(e);
    }
    return failsafeElement;
  }
}
