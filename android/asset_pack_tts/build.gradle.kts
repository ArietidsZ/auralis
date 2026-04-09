// TTS model asset pack - install-time delivery
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("asset_pack_tts")
    dynamicDelivery {
        deliveryType.set("install-time")  // Bundled with app install
    }
}

// Place INT4 quantized ONNX models in:
//   asset_pack_tts/src/main/assets/tts/
//     speaker_encoder_int4.onnx
//     talker_lm_int4.onnx
//     vocoder_int4.onnx
//     tokenizer/tokenizer.json
