// inJsonWithAdditionalData format
// json|||field1:value1###field2:value2###fieldN:valueN

function transform(inJsonWithAdditionalData) {
    var result = {};
    var inJson, kv;
    var splitAdditionalDataWithJSON, splitAdditionalDataKV;
    var obj, keys, numKeys;
    if (inJsonWithAdditionalData != null && inJsonWithAdditionalData != "") {
        splitAdditionalDataWithJSON = inJsonWithAdditionalData.split("|||");
        
        inJson = splitAdditionalDataWithJSON[0];
        obj = JSON.parse(inJson);
        keys = Object.keys(obj.payload);
        numKeys = keys.length;
        for (idx = 0; idx < numKeys; idx++){
            result[keys[idx].replace(/([^A-Za-z0-9])/g, "_")] = obj.payload[keys[idx]];
        }
        result["insert_timestamp"] = new Date().valueOf();

        additionalData = splitAdditionalDataWithJSON[1];
        if (additionalData){
            splitAdditionalDataKV = additionalData.split("###");
            additionalDataLen = splitAdditionalDataKV.length;
            for (idx = 0; idx < additionalDataLen; idx++){
                kv = splitAdditionalDataKV[idx].split(":");
                result[kv[0].replace(/([^A-Za-z0-9])/g, "_")] = kv[1];
            }
        }
    }
    return JSON.stringify(result);
}
