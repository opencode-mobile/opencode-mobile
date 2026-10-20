import FactoryKit
import Observation
import SwiftUI
import UIKit

@Observable
@MainActor
final class DiagnosticLogsViewModel {
    private(set) var entryCount = 0
    private(set) var droppedBySoftCap = 0
    private(set) var retainMinutes = DiagnosticLogBuffer.retainMinutes
    private(set) var exportWindow = DiagnosticLogExportWindow.default
    private(set) var softCap = DiagnosticLogBuffer.softCap
    private(set) var areaCounts: [(area: String, count: Int)] = []
    private(set) var oldestTimestampLabel: String?
    private(set) var newestTimestampLabel: String?
    private(set) var isLoading = true
    private(set) var shareError: String?
    private(set) var shareFileURL: URL?

    private let buffer: DiagnosticLogBuffer
    private let redactor: () -> FeedbackLogRedactor

    /// `redactor` is built at share time so it knows the profile that is active then.
    init(
        buffer: DiagnosticLogBuffer = .shared,
        redactor: @escaping () -> FeedbackLogRedactor = {
            FeedbackLogRedactor(profile: Container.shared.activeConnectionManager().currentProfile())
        }
    ) {
        self.buffer = buffer
        self.redactor = redactor
    }

    var hasEntries: Bool { entryCount > 0 }

    func refresh() {
        isLoading = true
        let snapshot = buffer.snapshot()
        entryCount = snapshot.entries.count
        droppedBySoftCap = snapshot.droppedBySoftCap
        retainMinutes = snapshot.retainMinutes
        softCap = snapshot.softCap

        var counts: [DiagnosticLogArea: Int] = [:]
        for entry in snapshot.entries {
            counts[entry.area, default: 0] += 1
        }
        areaCounts = DiagnosticLogArea.allCases.compactMap { area in
            guard let count = counts[area], count > 0 else { return nil }
            return (area.rawValue, count)
        }

        oldestTimestampLabel = snapshot.entries.first.map { Self.timestampLabel(for: $0.timestampEpochMilliseconds) }
        newestTimestampLabel = snapshot.entries.last.map { Self.timestampLabel(for: $0.timestampEpochMilliseconds) }
        isLoading = false
    }

    func clear() {
        buffer.clear()
        shareFileURL = nil
        shareError = nil
        refresh()
    }

    func dismissShare() {
        shareFileURL = nil
    }

    func setExportWindow(_ window: DiagnosticLogExportWindow) {
        exportWindow = window
    }

    func prepareShare() {
        shareError = nil
        do {
            // Shared logs get the same address and credential masking as a feedback upload.
            let data = try buffer.exportJSONL(window: exportWindow, message: redactor().redact)
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent(DiagnosticLogAttachment.filename)
            if FileManager.default.fileExists(atPath: url.path) {
                try FileManager.default.removeItem(at: url)
            }
            try data.write(to: url, options: .atomic)
            shareFileURL = url
            refresh()
        } catch {
            shareFileURL = nil
            shareError = "Couldn't prepare diagnostic log for sharing."
        }
    }

    private static func timestampLabel(for epochMilliseconds: Int64) -> String {
        Date(timeIntervalSince1970: Double(epochMilliseconds) / 1_000)
            .formatted(date: .abbreviated, time: .standard)
    }
}

struct DiagnosticLogsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model = DiagnosticLogsViewModel()
    @State private var showClearConfirmation = false
    @State private var showShareSheet = false
    @State private var showExportWindowPicker = false

    /// Set when shown as the trailing root of the tablet Settings split view.
    var detailControl: OpenCodeSplitPaneDetailControl? = nil

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Diagnostic Logs",
                leading: OpenCodeSplitPane.detailLeading(detailControl, back: { dismiss() }),
                showDivider: true
            )

            VStack(spacing: 0) {
            ScrollView {
                LazyVStack(spacing: 8) {
                    if model.isLoading {
                        OpenCodeStatusStack(spinner: "Loading diagnostic logs")
                            .padding(24)
                    } else {
                        OpenCodeListItem(
                            title: "Entries",
                            subtitle: model.hasEntries
                                ? "\(model.entryCount) redacted lines in the kept window"
                                : "No entries in the kept window yet",
                            style: .card
                        )
                        OpenCodeListItem(
                            title: "Kept",
                            subtitle: "Last \(model.retainMinutes) minutes",
                            style: .card
                        )
                        OpenCodeListItem(
                            title: "Export",
                            subtitle: model.exportWindow.label,
                            style: .card,
                            action: { showExportWindowPicker = true }
                        )
                        OpenCodeListItem(
                            title: "Soft cap",
                            subtitle: model.droppedBySoftCap > 0
                                ? "\(model.softCap) max · \(model.droppedBySoftCap) dropped oldest"
                                : "\(model.softCap) max · none dropped",
                            style: .card
                        )
                        OpenCodeListItem(
                            title: "Format",
                            subtitle: "JSONL · secrets and message bodies redacted",
                            style: .card
                        )
                        if let oldest = model.oldestTimestampLabel, let newest = model.newestTimestampLabel {
                            OpenCodeListItem(
                                title: "Range",
                                subtitle: oldest == newest ? oldest : "\(oldest) → \(newest)",
                                style: .card
                            )
                        }
                        if !model.areaCounts.isEmpty {
                            OpenCodeListItem(
                                title: "Areas",
                                subtitle: model.areaCounts
                                    .map { "\($0.area) \($0.count)" }
                                    .joined(separator: " · "),
                                style: .card
                            )
                        }
                        if let shareError = model.shareError {
                            OpenCodeInlineNotice(message: shareError, tone: .error)
                                .padding(.top, 4)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 24)
            }

            VStack(spacing: 8) {
                OpenCodeButton(
                    variant: .fullWidthText(title: "Clear"),
                    size: .large,
                    enabled: model.hasEntries,
                    contrast: .low
                ) {
                    showClearConfirmation = true
                }
                OpenCodeButton(
                    variant: .fullWidthText(title: "Share"),
                    size: .large,
                    contrast: .high
                ) {
                    model.prepareShare()
                    if model.shareFileURL != nil {
                        showShareSheet = true
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 16)
            }
            .openCodeReadableColumn()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .onAppear { model.refresh() }
        .openCodeDialog(
            isPresented: $showClearConfirmation,
            title: "Clear diagnostic logs?",
            subtitle: "This removes the stored diagnostic log.",
            primaryOption: OpenCodeDialogOption(title: "Clear") {
                model.clear()
                showClearConfirmation = false
            },
            secondaryOption: OpenCodeDialogOption(title: "Cancel") {
                showClearConfirmation = false
            }
        )
        .openCodeBottomSheet(isPresented: $showExportWindowPicker, title: "Export window") {
            ForEach(DiagnosticLogExportWindow.allCases, id: \.self) { window in
                OpenCodeBottomSheetEntry(
                    title: window.label,
                    selected: window == model.exportWindow,
                    action: {
                        model.setExportWindow(window)
                        showExportWindowPicker = false
                    }
                )
            }
        }
        .sheet(isPresented: $showShareSheet, onDismiss: {
            model.dismissShare()
        }) {
            if let url = model.shareFileURL {
                ActivityShareView(items: [url])
            }
        }
    }
}
