import Testing
@testable import DialectInterpreter

struct WaveformLevelMappingTests {
    @Test func activeLevelIsBounded() {
        for rms: Float in [-1, 0, WaveformLevel.quietFloor] {
            #expect(WaveformLevel.levelFraction(amplitude: rms, isActive: true) == 0)
        }
        for rms in [WaveformLevel.loudCeil, WaveformLevel.loudCeil * 8] {
            #expect(WaveformLevel.levelFraction(amplitude: rms, isActive: true) == 1)
        }
        for step in 0..<200 {
            let level = WaveformLevel.levelFraction(amplitude: Float(step) / 50, isActive: true)
            #expect((0...1).contains(level))
        }
    }

    @Test func quietSpeechRemainsVisible() {
        let quiet = WaveformLevel.levelFraction(amplitude: 0.01, isActive: true)
        let normal = WaveformLevel.levelFraction(amplitude: 0.1, isActive: true)
        #expect(abs(quiet - 0.1505) <= 0.002)
        #expect(abs(normal - 0.6505) <= 0.002)
        #expect(quiet >= WaveformLevel.restLevel + 0.02)
        #expect(normal >= 0.6 && normal < 1)
    }

    @Test func levelIsMonotonic() {
        // Sample the range plus adjacent floats at both clamp boundaries.
        // nextUp across the entire range would need >100 million assertions.
        let boundaries = [WaveformLevel.quietFloor, WaveformLevel.loudCeil]
        let inputs = ((0...1000).map { Float($0) / 1000 }
            + boundaries.flatMap { [$0.nextDown, $0, $0.nextUp] }).sorted()
        var previous: Float = -1
        for rms in inputs {
            let level = WaveformLevel.levelFraction(amplitude: rms, isActive: true)
            #expect(level >= previous, "rms=\(rms) regressed")
            previous = level
        }
    }

    @Test func inactiveAndInvalidInputsUseRestLevel() {
        for amplitude in [Float.nan, .infinity, -.infinity, 0.3] {
            #expect(WaveformLevel.levelFraction(amplitude: amplitude, isActive: false)
                    == WaveformLevel.restLevel)
        }
        for amplitude in [Float.nan, .infinity, -.infinity] {
            #expect(WaveformLevel.levelFraction(amplitude: amplitude, isActive: true)
                    == WaveformLevel.restLevel)
        }
    }

    @Test func inactiveInputsCannotChangeBarGeometry() {
        for amplitude: Float in [0, 0.01, 0.1, 1] {
            let rest = WaveformLevel.levelFraction(amplitude: amplitude, isActive: false)
            for index in 0..<WaveformLevel.barCount {
                let height = WaveformLevel.barHeightFraction(level: rest, index: index)
                let expected = WaveformLevel.barHeightFraction(level: WaveformLevel.restLevel, index: index)
                #expect(abs(height - expected) <= 1e-6)
            }
        }
    }

    @Test func barWindowIsSymmetricAndCenterWeighted() {
        #expect(abs(WaveformLevel.barWindow(index: 0) - 0.55) <= 1e-6)
        #expect(abs(WaveformLevel.barWindow(index: 3) - 1) <= 1e-6)
        for index in 0..<WaveformLevel.barCount {
            #expect(abs(WaveformLevel.barWindow(index: index)
                - WaveformLevel.barWindow(index: 6 - index)) <= 1e-6)
        }
        #expect(WaveformLevel.barWindow(index: 3) > WaveformLevel.barWindow(index: 0))
    }

    @Test func barHeightsRemainVisibleAndBounded() {
        for level: Float in [0, 0.15, 0.65, 1] {
            for index in 0..<WaveformLevel.barCount {
                let height = WaveformLevel.barHeightFraction(level: level, index: index)
                #expect((WaveformLevel.minFraction...1).contains(height))
            }
        }
        #expect(WaveformLevel.barHeightFraction(level: 0.65, index: 3)
                > WaveformLevel.barHeightFraction(level: 0.65, index: 0))
    }

    @Test func singleBarRemainsFinite() {
        #expect(abs(WaveformLevel.barWindow(index: 0, barCount: 1) - 0.55) <= 1e-6)
        let height = WaveformLevel.barHeightFraction(level: 0.5, index: 0, barCount: 1)
        #expect((WaveformLevel.minFraction...1).contains(height))
    }
}
