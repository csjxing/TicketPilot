import Foundation
import Vision
import AppKit

let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.recognitionLanguages = ["zh-Hans", "en-US"]
request.usesLanguageCorrection = false
func recognize(_ path: String) throws -> String {
    try VNImageRequestHandler(url: URL(fileURLWithPath: path)).perform([request])
    let rows: [[String: Any]] = (request.results ?? []).compactMap { item in
        guard let t = item.topCandidates(1).first else { return nil }
        let b = item.boundingBox
        return ["text": t.string, "confidence": t.confidence, "x": b.midX, "y": 1-b.midY,
                "left": b.minX, "right": b.maxX, "top": 1-b.maxY, "bottom": 1-b.minY]
    }
    return String(data: try JSONSerialization.data(withJSONObject: rows), encoding: .utf8)!
}
if CommandLine.arguments.dropFirst().first == "--serve" {
    while let path = readLine() {
        autoreleasepool {
            do { print(try recognize(path)) }
            catch { print("{\"error\":\"recognition_failed\"}") }
            fflush(stdout)
        }
    }
} else {
    print(try recognize(CommandLine.arguments[1]))
}
