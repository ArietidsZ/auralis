import Testing
import Foundation
@testable import DialectInterpreter

/// Golden tests for the [1, T, 128] log-mel frontend (n_fft 1024, hop 256,
/// slaney norm) in Qwen3TtsProtocol. The vectors were generated through the
/// identical Swift code path with the LCG input construction below and are
/// pinned as Float bit patterns; comparisons allow a 4-ulp drift so the test
/// survives libm round-off differences across Apple platforms without masking
/// a real frontend change (windowing/FFT/filterbank edits move values by far
/// more than 4 ulps).
@MainActor
struct Qwen3TtsMelTests {

    /// Must stay byte-identical to the golden generator's input construction;
    /// changing it changes the inputs and invalidates every vector below.
    private static func lcgAudio(count: Int, seed: UInt64) -> [Float] {
        var s = seed
        var out = [Float]()
        out.reserveCapacity(count)
        for _ in 0..<count {
            s = s &* 6364136223846793005 &+ 1442695040888963407
            out.append(Float(Int64(bitPattern: s >> 11) % 20000) / 20000.0 - 1.0)
        }
        return out
    }

    private static func parseGolden(_ hex: String) -> [Float] {
        hex.split(whereSeparator: \.isWhitespace).map { Float(bitPattern: UInt32($0, radix: 16)!) }
    }

    /// Compares frame count, value count and per-value bit patterns (4-ulp
    /// tolerance), reporting the first mismatch only.
    private static func expectGolden(_ actual: [Float], _ golden: [Float], frames: Int,
                                     sourceLocation: SourceLocation = #_sourceLocation) {
        #expect(golden.count == frames * 128, "golden vector is malformed", sourceLocation: sourceLocation)
        #expect(actual.count == frames * 128,
                "expected \(frames * 128) mel values, got \(actual.count)", sourceLocation: sourceLocation)
        for (i, pair) in zip(actual, golden).enumerated() {
            let (v, g) = pair
            if abs(v - g) > 4 * g.ulp {
                Issue.record("mel[\(i)] = \(v) (0x\(String(v.bitPattern, radix: 16))) vs golden \(g) (0x\(String(g.bitPattern, radix: 16)))",
                             sourceLocation: sourceLocation)
                return
            }
        }
    }

    @Test func lcgNoise1280MatchesGolden() {
        // 1280 LCG samples at 24 kHz → 5 frames × 128 mels.
        let result = Qwen3TtsProtocol.logMelSpectrogram(
            Self.lcgAudio(count: 1280, seed: 0xA5A5_5A5A_0000_0042), sampleRate: 24000)
        #expect(result.frames == 5)
        Self.expectGolden(result.data, Self.parseGolden(Self.lcg1280GoldenHex), frames: 5)
    }

    @Test func impulse256MatchesGolden() {
        // A single impulse at sample 100, exactly one hop long: the shortest
        // input the frontend accepts, exercising the reflect-pad boundary.
        var impulse = [Float](repeating: 0, count: 256)
        impulse[100] = 1.0
        let result = Qwen3TtsProtocol.logMelSpectrogram(impulse, sampleRate: 24000)
        #expect(result.frames == 1)
        Self.expectGolden(result.data, Self.parseGolden(Self.impulse256GoldenHex), frames: 1)
    }

    @Test func dc2048MatchesGoldenStructure() {
        // 2048 samples of DC at 0.25 → 8 identical frames. Frame structure:
        // mel bin 0 captures the DC leakage (0x3f43a125); every other bin has
        // no filterbank support over a DC spectrum and lands exactly on the
        // clamp floor log(1e-5) (0xc13834f1) — so the golden collapses to two
        // bit patterns.
        let result = Qwen3TtsProtocol.logMelSpectrogram(
            [Float](repeating: 0.25, count: 2048), sampleRate: 24000)
        #expect(result.frames == 8)
        #expect(result.data.count == 8 * 128)
        for (i, v) in result.data.enumerated() {
            let expected = Float(bitPattern: i % 128 == 0 ? 0x3f43a125 : 0xc13834f1)
            if v.bitPattern != expected.bitPattern, abs(v - expected) > 4 * expected.ulp {
                Issue.record("mel[\(i)] = \(v) vs golden \(expected)")
                return
            }
        }
    }

    @Test func frameCountFollowsConvLayout() {
        // frames = 1 + (count + 2*pad - n_fft) / hop with n_fft 1024, hop 256,
        // pad 384; data holds frames × 128 mels.
        for (count, frames) in [(256, 1), (1024, 4), (1280, 5), (2048, 8)] {
            let result = Qwen3TtsProtocol.logMelSpectrogram(
                [Float](repeating: 0.5, count: count), sampleRate: 24000)
            #expect(result.frames == frames, "count \(count): frames \(result.frames) != \(frames)")
            #expect(result.data.count == frames * 128, "count \(count): payload size mismatch")
        }
    }

    @Test func reflectIndexMirrorsTorchReflectPad() {
        // period = 2*(length-1); indices fold back into [0, length).
        #expect(Qwen3TtsProtocol.reflectIndex(0, length: 5) == 0)
        #expect(Qwen3TtsProtocol.reflectIndex(4, length: 5) == 4)
        #expect(Qwen3TtsProtocol.reflectIndex(-1, length: 5) == 1)
        #expect(Qwen3TtsProtocol.reflectIndex(5, length: 5) == 3)
        #expect(Qwen3TtsProtocol.reflectIndex(9, length: 5) == 1)
        #expect(Qwen3TtsProtocol.reflectIndex(-9, length: 5) == 1)
        #expect(Qwen3TtsProtocol.reflectIndex(13, length: 5) == 3)
        // Length 1 has no period; every index maps to the sole sample.
        #expect(Qwen3TtsProtocol.reflectIndex(0, length: 1) == 0)
        #expect(Qwen3TtsProtocol.reflectIndex(7, length: 1) == 0)
    }

    // MARK: golden vectors (Float bit patterns, hex)

    private static let lcg1280GoldenHex = """
3fbe35f1 c00537df bfbfa6dd c00001fe c00cc41f bff884c8 bfad0c10 bfc5f894
c015be82 c0002ef2 c022f71e c00b167d bfef8186 bfe433a3 bfdfbc9b bfe88ee9
c013903a bfbfb25e bfa462cc bfbe57f1 bf992ebb bfb09aec bff0ac1b bff44176
c00616cc bff4bf97 bfb1a194 bfabebdf c0148435 bfd702a1 bf6ac804 bfb880b7
bfba0c0a bf9a393f bfa94c27 bfe96fa1 bfc554cd c0008d3d c00c485b bfd2b755
c001f320 bfa57f38 bf992313 c00d4b1f bfa7550b bfb6e504 bfab96f3 bf7a1f92
bf5c935c bfeae396 c01069a2 bff3988f bf8984f7 bfaac9be bf80978a bf9ba6df
bf88beb9 bffa6649 c01ace56 c00d5ab1 bfc3dd39 bf9a4976 bfa53c16 c0133eb0
c00abe7f bff6eea9 bfc0678d bf77b1ab bf6fd1c1 bfa21dbe bfed12c8 bfd94ff5
bfa4b4f3 bfe5d8e1 bfe8d064 bff0160b c007ccc5 bfd786e0 bfc70cec bf9e70c2
bf6159ee bfa06627 bff2f7c7 bfe09696 bfbc839f bfa0d21e c005ee2e bfeef138
bfba924b bfd40367 bf8b69f9 bfa72fae bf89c687 bfd0d747 bff27979 bfefe6a6
bfc9c5a3 bfd6f0ef bfacc980 bfb5f70f bfa06800 bfd0b3a2 bfc25fdd bfb01cf0
bfb73de1 bf96bdd8 bfbeae5b bff0d98d c0086af0 bff4a24f bfb66549 bfad8d0c
bff287e7 bff039e6 bfe0240f bfe25da1 bfc93f9a bf8c6d8c bfc17bce bfc05959
bfba2ba9 bfbf7dbc bfb0441b bfd26ca7 bfba88ea bfe17dc8 bfe6287a bfc97c07
3fbd4126 bfb86337 bfb95d3c bff0d8e5 c00a5b8d c005eabf bfb055d3 bfa5a633
bff47787 bff12ed5 bfd31f03 bfc78204 c001619e bfff519d bfc2b30c bf7671ce
bf9c2a03 bf6bf786 bf52aec5 bfb98974 bfcb4621 c006c184 bf9e808b bfbb0c6d
c02d632a c005f0cd bfb949c5 bf9d8071 bfc408ec bf9f6447 bf370dd8 bf9768b1
bff6c826 bfb13b2b bf7ea3a3 bf90d63b bfc55fac bffc6b61 bffcef64 bfdefb41
bfd2c4cb bf76c5b1 bf9d52ba c00f34cb bf98d107 bf980226 bfac6288 bf9f7a67
bf9c6ded c007fe2f bff9d7b4 bfd742a9 bfaf62db bfe0f311 c00e8c29 bfdca499
bfe53f1f c0029d38 c0114a02 bfb6c16e bf9989a8 bfcf5a6e bfca3ff2 bfd7d788
c010701c bff3f96d bf9fa130 bf7ab53d bfb98bb1 c01094de bfcf1a74 bfbe3527
bfb2f663 bfc87aea bfcd47d3 bfb42bb2 bfd96d24 bfcf1e7e bfb7e311 bfb3af79
bfb72ffd bfa25377 bfcd8215 bfa76344 bfb5020b bfd4b367 bffee71b bfb724cc
bfc672a1 bfc7db14 bfb0c830 bfcf7148 bfb8fec6 bfc1de0c bfb68ae9 c00a1282
bfd602a7 bfa5e24e bfb064bf bfb63b6f bfd615d9 bfaf0fea c0059c3e bfcf5815
bf96a7dd bfaf9723 bfdce223 bfa50767 bfdbb1db bfd30a78 bfca60ae bfcd7f4f
bfd1def5 bfcbbf2b bfc58b34 bfd4c69b bfcb4359 bfb9d285 bfd458d1 bfba9085
bfb36bcf bfbd7bfa bfbb2329 bfca4ec6 bfaabed8 bfdd85d0 bff3844f bfba946d
3fb96772 c001b186 bff77298 bfe0b59e bfc2e5c1 bff79ad5 bfc4dd96 bfa11ecf
c01252d1 bfe10765 bfb4698a bf951cae bfd6198a bfcf1344 bf3381ea bf5fe8a5
bfc73d60 bf99cf64 bf6b6cf9 bf877d0d bfd7ab77 bfc82db7 bf9b43a6 bfc51e4f
c0194cc0 c0217aa1 c00856e9 bfd5b15a c0045e37 bfc79cc1 bf9e697c bfca6f9b
c00ca1bb bfde7ee4 bfdfc0d9 bfcb7500 c02451b3 c0398bdd c0429204 c0125130
c0108b99 bfab23d8 bfa5649c c02aedd2 c001c28c bfb8cd84 bfca5bb0 bf7438fe
bfa3ff26 c0205831 c02e192e bfe34eb6 bfd024ea c0006614 bff02e30 bfbd2016
c024f0c9 bff870de bffb01c6 bf853a8a bfd290d5 bfeb2558 bfe57cd7 bff4e1e1
bff37efd c0071b80 bf90bc43 bfa14ae5 bffbe08b c00d44bb bf9fb549 bf91ba44
bfbc2b33 bfbd4ce4 c005c5c2 bfd8e0d1 bfeae829 bfb0ad0e bfad77c0 bfbdf874
bfc6297a bf9cfa64 bfa5aa17 bf9b93ad bfc042c5 bff054ce bfde3d59 bf885cf9
bfa7edfa bfc1d90e bfc9ad7b bfae2083 bfb9fb0c bfc9a138 bfbd032f bffef5b7
bfe8ebbc bf8edec9 bf9dc08e bfb3dc0c c0074881 bfa1b24c bfff8882 bfd9aa8f
bfa9cbcf bfdb41fb bfd8484f bfc439a9 bfc88da1 bff2aa0b bfe6e489 bfd3871f
bfa95079 bfcabbeb bfdaa12c bfc23bba bfda694a bfc2060a bfc659d1 bfd6f372
bfc192ca bfd4b3fa bfbcbb63 bfbfc087 bfb4adf3 bfd2ecd7 c0010b87 bfb21d92
3fbce210 bfffd2f5 bfddab37 bfaf9a51 bfd938b6 c04f3312 c026059b bfc55efa
bfbb2791 bfa1ab89 bf6ecd64 bf6dd7d9 bfaa45e5 bf82227a bedc2769 bf32ea55
bfc58f04 bfd7adde bf935a58 bfc1a176 c024bd88 c002b78b bff9824e c02f9afa
bfee583e bfe9061d bfe673b0 bfa26fb7 bfbc9b60 c0216793 c03e0282 bfdf7abf
c00c0e79 c0235687 c012830f c01f8265 c01b59c6 c007d48c bfff1ac3 bfd94800
c001f93a bfe032bd bfe938fd c052b3ba c00ed165 bfd23a63 c00661db bfc16d3f
bfd25a9b bff0a710 bffe2efc bfbb3ea3 bfbdf2be bfc9a1e4 bfaeede4 bfc1ebe8
bfe3f199 bfd12ef9 bf915c8c bf875800 c00583e9 bffd90a4 bfa53886 bfc73dcb
bfdc82e6 bfdcf4a2 bfbf2cf1 bfbe5279 bff119f9 bf9408a0 bf71a5c7 bfa12e99
bfde29ed bff9b1ea bff69d8e bfbdd75c bfbf9852 bff5d3e5 bf965cc5 bf9f442c
bff32b9d c00d6842 bfea0d5c bf8ea108 bfd0b592 bfe35688 bfcca42e bf9e9218
bf9c1db6 bfee4626 bffe64ca bfa04339 bfcc0e28 bfd13132 bfc89449 bfbb520a
bfc9e53a bfb6358c bf9c9dcd bfdc6cd5 bfef5ae5 bfdea8cb bfd29855 bfc8bc89
bfe2e135 bfce82fc bfcdbf98 bfe2abaf bfa241fb bfc21a0c bfe7a758 bfc97b84
bfb2dbbe bfc50c76 bfd6fc63 bfb56dac bfa384ab bfadcb4e bfb59312 bfc89953
bfc0879d bfcc7028 bfdd034c bfd59238 bfd654b5 bfcc8b8e bfd8aa76 bfbb865b
3fbfe0a5 c0132d91 c00e1af4 bfd32ea2 bffd7f0e c03a50a7 c032fc6a c0234a92
c01ef48b c0038f4f bfd649a2 bfd3f78a c00c38ec bfa79a2d bf872593 bf4f3eda
bfe194fa c0076f7a bfc39bf9 bf8c66aa bfa17199 c00126b8 c05337f2 c027f8b0
c00f7ae0 bfcccf8c bfa6b28a bf59f295 bf912885 c009cfa7 c021417c c00d2905
c01f0e58 c040414f c0289f9d c04cedae c04a8579 c0292311 bfbce85d bfc8b889
c013e880 bfebeae9 bfe828ae c055136c c0159778 bfdb3e0c c026dcb3 c02b78e3
c00faece bfc50a33 bf6f9fbe bf648a24 bfabebd9 c006808b c00ead3b bff3a292
bfebf825 bfb3c622 bfb3f887 c002431a c024dcca c026e4c1 bfbb94e5 bfaba21f
c0050886 bfd85238 bfb6cfca bfbc72e0 bfaf80c4 bf7f97a2 bfc1b0bd bfc28648
bff44e8d bfc20088 bfcb5c7b bf6c0424 bf9e16d0 bfcd49bd bf7de60e bf8bbb08
c01618ca c00ce08b c0060997 bf91b3d9 c002af0d bfb3886c bfce2f78 bf877c68
bfac5768 c003ec7b bfb19fc2 bf93fbe4 c00c104f c00514dd bfc3ae94 bf90a224
bf9bc93a bfed4045 bfca79e5 c0092ef3 bfea38aa bfe81f60 bfd5060e bfc1d13b
bfd77984 bfd8b80a bfc93613 bfbb0708 bf96d5ff bfc82fcf bfe99013 bfd38183
bfb915b3 bfc387d0 bfcb6bbd bfac1423 bf7d0c79 bfc1d558 bff1c8a1 bfd6fa86
c002f767 bfafc743 bfc65a64 bfdae7f5 bfe003cb bfc3351f bfc394c2 bfd472e9
"""

    private static let impulse256GoldenHex = """
c04c3720 c05fd4aa c042b355 c0377192 c0314e64 c04254f3 c0728474 c043708f
c025084f c047346a c054d0b1 c043b7a5 c03defb7 c02b85f0 c041b7b8 c08728ce
c0439b3d c029450d c041af5e c04e31e1 c0467035 c044cbea c0241143 c047ee23
c0935cb8 c0416ff9 c02df3a6 c03be97c c04982c9 c04c9867 c043ce4b c02333e5
c04d3326 c07e3e9a c03f3bd0 c0331581 c0362512 c0455ee5 c05263d9 c03de6d2
c02ddbe9 c05ab823 c04dcb97 c038ec5d c02fa64e c0510c95 c054389a c02c8da4
c04d20a8 c04a9f22 c033b08f c03d8926 c0654222 c0308551 c04970cc c0425838
c030f854 c06eca48 c035f32a c0406136 c045ba31 c0387f7d c05cc394 c0339d77
c04c1de7 c03567fa c0560364 c0359c0a c04b339f c03b07ba c0484c9c c03b743a
c046ba4d c0432533 c03ad730 c04dbfc8 c03e4033 c03dee7b c04b63bf c03f6a05
c040f11e c041b134 c0423fe7 c0444c78 c03fc54f c0421040 c03f5092 c045ce7c
c03ea041 c0477fb2 c03eda16 c0458519 c03fb300 c0428fe4 c041dd1e c040da29
c041eeb0 c0416628 c0413c02 c044b76b c041c966 c040c427 c0414029 c04258e2
c0407686 c042f76c c0413163 c044287d c0402f57 c042351e c0416a4e c041d351
c0428eff c0428791 c041e908 c041a292 c041e5f8 c0428a00 c0423c1d c0421201
c041f684 c041f9a7 c0424367 c0424eef c0417126 c0417268 c042d7e1 c041c4ea
"""
}
