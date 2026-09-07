// ASR model asset pack - install-time delivery
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("asset_pack_asr")
    dynamicDelivery {
        deliveryType.set("install-time")  // Bundled with app install
    }
}

// Place quantized ONNX models in:
//   asset_pack_asr/src/main/assets/asr/
//     asr_encoder_int4.onnx
//     asr_decoder_int4.onnx
//     tokenizer/tokenizer.json
