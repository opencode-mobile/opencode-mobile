#if HIGHLIGHT_LAB
import Foundation
import SwiftUI

/// `-labScreen <screen>` opens one Lab screen at launch, for checks and screenshots on a
/// simulator: `home`, `dataSets`, `paste`, `benchmark`, or `viewer=<data set id>`.
/// `-labAutoRun YES` also starts the benchmark run.
nonisolated enum HighlightLabScreenLaunch: Equatable {
    case home
    case dataSets
    case paste
    case benchmark(autoRun: Bool)
    case viewer(String)

    static var requested: HighlightLabScreenLaunch? {
        parse(ProcessInfo.processInfo.arguments)
    }

    static func parse(_ arguments: [String]) -> HighlightLabScreenLaunch? {
        guard let index = arguments.firstIndex(of: "-labScreen"), arguments.indices.contains(index + 1) else { return nil }
        let value = arguments[index + 1]
        let autoRun = arguments.firstIndex(of: "-labAutoRun").map { arguments.indices.contains($0 + 1) && arguments[$0 + 1] == "YES" } ?? false
        switch value {
        case "home": return .home
        case "dataSets": return .dataSets
        case "paste": return .paste
        case "benchmark": return .benchmark(autoRun: autoRun)
        default:
            guard value.hasPrefix("viewer=") else { return nil }
            return .viewer(String(value.dropFirst("viewer=".count)))
        }
    }
}

struct HighlightLabScreenLaunchView: View {
    let screen: HighlightLabScreenLaunch
    private let service = KMPHighlightLabService.shared
    @State private var benchmarkModel = HighlightLabBenchmarkModel(service: KMPHighlightLabService.shared)

    var body: some View {
        NavigationStack {
            switch screen {
            case .home:
                HighlightLabHomeView(service: service)
            case .dataSets:
                HighlightLabDataSetsView(service: service)
            case .paste:
                HighlightLabPasteView(service: service)
            case let .benchmark(autoRun):
                HighlightLabBenchmarkView(model: benchmarkModel)
                    .task { if autoRun { benchmarkModel.start() } }
            case let .viewer(id):
                HighlightLabViewerView(input: .catalog(id: id), service: service)
            }
        }
    }
}
#endif
