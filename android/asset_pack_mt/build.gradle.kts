// MT model asset pack - install-time delivery
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("asset_pack_mt")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

// Place AngelSlim/Hy-MT model files in:
//   asset_pack_mt/src/main/assets/mt/
//     Hy-MT1.5-1.8B-1.25bit.gguf
//     manifest.json
