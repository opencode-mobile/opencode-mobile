import Foundation

/// OPE-124 product Analytics taxonomy. Feature / Screen Model code calls these
/// helpers only — never Firebase. All sends go through
/// `OpenCodeReleaseObservability.logProductEvent` (consent + Debug gate).
enum ProductAnalytics {
    enum Event {
        static let screenView = "product_screen_view"
        static let userAction = "product_user_action"
    }

    enum Presentation {
        static let route = "route"
        static let sheet = "sheet"
        static let dialog = "dialog"
        static let overlay = "overlay"
        static let embedded = "embedded"
        static let fullscreen = "fullscreen"
    }

    enum Trigger {
        static let push = "push"
        static let present = "present"
        static let resume = "resume"
    }

    /// Allowed `Param.outcome` values. Enum-only, never a server message.
    enum Outcome {
        static let success = "success"
        static let failure = "failure"
        static let blocked = "blocked"
    }

    enum Screen {
        static let appLoading = "app_loading"
        static let appOnboarding = "app_onboarding"
        static let appOnboardingSetupGuide = "app_onboarding_setup_guide"
        static let home = "home"
        static let homeConnectionEmpty = "home_connection_empty"
        static let homeProbeFailed = "home_probe_failed"
        static let connectionSetup = "connection_setup"
        static let pairingQRScanner = "pairing_qr_scanner"
        static let homeAddProject = "home_add_project"
        static let homeNewSessionProjectPicker = "home_new_session_project_picker"
        static let homeFilterProjectPicker = "home_filter_project_picker"
        static let homeSessionActions = "home_session_actions"
        static let homeRenameSession = "home_rename_session"
        static let homeForkSession = "home_fork_session"
        static let homeForkResult = "home_fork_result"
        static let homeDeleteSessionConfirm = "home_delete_session_confirm"
        static let homeRemoveProjectConfirm = "home_remove_project_confirm"
        static let terminal = "terminal"
        static let terminalsCatalog = "terminals_catalog"
        static let sessionChat = "session_chat"
        static let sessionOverflow = "session_overflow"
        static let sessionCommands = "session_commands"
        static let sessionRename = "session_rename"
        static let sessionFork = "session_fork"
        static let sessionForkResult = "session_fork_result"
        static let sessionDeleteConfirm = "session_delete_confirm"
        static let sessionAttachmentSource = "session_attachment_source"
        static let sessionModelPicker = "session_model_picker"
        static let sessionModePicker = "session_mode_picker"
        static let sessionVariantPicker = "session_variant_picker"
        static let sessionMessageActions = "session_message_actions"
        static let sessionPendingPrompts = "session_pending_prompts"
        static let sessionToolDetail = "session_tool_detail"
        static let sessionToolGroup = "session_tool_group"
        static let sessionTodos = "session_todos"
        static let sessionContext = "session_context"
        static let changes = "changes"
        static let changesFileViewer = "changes_file_viewer"
        static let settings = "settings"
        static let settingsAppearance = "settings_appearance"
        static let settingsThemePicker = "settings_theme_picker"
        static let settingsSyntaxPicker = "settings_syntax_picker"
        static let settingsRecentWindowPicker = "settings_recent_window_picker"
        static let settingsClearCacheConfirm = "settings_clear_cache_confirm"
        static let settingsClearAllDataConfirm = "settings_clear_all_data_confirm"
    }

    enum Action {
        static let onboardingSkip = "onboarding_skip"
        static let onboardingNext = "onboarding_next"
        static let onboardingConnect = "onboarding_connect"
        static let onboardingOpenSetupGuide = "onboarding_open_setup_guide"
        static let onboardingSwipePage = "onboarding_swipe_page"
        static let setupGuideBack = "setup_guide_back"
        static let setupGuideOpenDocs = "setup_guide_open_docs"

        static let homeOpenSettings = "home_open_settings"
        static let homeOpenTerminal = "home_open_terminal"
        static let homePullToRefresh = "home_pull_to_refresh"
        static let homeOpenSession = "home_open_session"
        static let homeLongPressSession = "home_long_press_session"
        static let homeCreateSession = "home_create_session"
        static let homeOpenNewSessionProjectPicker = "home_open_new_session_project_picker"
        static let homeOpenProjectPicker = "home_open_project_picker"
        static let homeAddProject = "home_add_project"
        static let homeRemoveProject = "home_remove_project"
        static let homeReorderProjects = "home_reorder_projects"
        static let homeSelectSessionFilter = "home_select_session_filter"
        static let homeDismissRemovalSnackbar = "home_dismiss_removal_snackbar"
        static let homeConnect = "home_connect"
        static let homeRetryProbe = "home_retry_probe"
        static let homeEditConnection = "home_edit_connection"

        static let connectionBackCancel = "connection_back_cancel"
        static let connectionEditBaseUrl = "connection_edit_base_url"
        static let connectionEditUsername = "connection_edit_username"
        static let connectionEditPassword = "connection_edit_password"
        static let connectionTest = "connection_test"
        static let connectionSave = "connection_save"
        static let connectionOpenPairingQR = "connection_open_pairing_qr"
        static let connectionPairingQRScan = "connection_pairing_qr_scan"

        static let addProjectDismiss = "add_project_dismiss"
        static let addProjectGoUp = "add_project_go_up"
        static let addProjectEnterDirectory = "add_project_enter_directory"
        static let addProjectSelectCurrent = "add_project_select_current"
        static let addProjectRetryNavigator = "add_project_retry_navigator"
        static let newSessionPickerDismiss = "new_session_picker_dismiss"
        static let projectPickerDismiss = "project_picker_dismiss"
        static let newSessionPickerSelectProject = "new_session_picker_select_project"

        static let sessionActionsDismiss = "session_actions_dismiss"
        static let sessionActionsRename = "session_actions_rename"
        static let sessionActionsFork = "session_actions_fork"
        static let sessionActionsArchive = "session_actions_archive"
        static let sessionActionsDelete = "session_actions_delete"
        static let homeRenameEditTitle = "home_rename_edit_title"
        static let homeRenameSave = "home_rename_save"
        static let homeRenameCancel = "home_rename_cancel"
        static let homeForkEditTitle = "home_fork_edit_title"
        static let homeForkConfirm = "home_fork_confirm"
        static let homeForkCancel = "home_fork_cancel"
        static let homeForkOpen = "home_fork_open"
        static let homeForkStay = "home_fork_stay"
        static let homeDeleteConfirm = "home_delete_confirm"
        static let homeDeleteCancel = "home_delete_cancel"
        static let homeRemoveProjectConfirm = "home_remove_project_confirm"
        static let homeRemoveProjectCancel = "home_remove_project_cancel"

        static let chatBack = "chat_back"
        static let chatOpenOverflow = "chat_open_overflow"
        static let chatOpenSessionContext = "chat_open_session_context"
        static let chatOpenChanges = "chat_open_changes"
        static let chatOpenChangesFromTool = "chat_open_changes_from_tool"
        static let chatOpenChildSession = "chat_open_child_session"
        static let chatOpenTodos = "chat_open_todos"
        static let chatScrollToEnd = "chat_scroll_to_end"
        static let chatOpenMessageActions = "chat_open_message_actions"
        static let chatOpenToolDetail = "chat_open_tool_detail"
        static let chatOpenToolGroup = "chat_open_tool_group"
        static let chatDismissSnackbar = "chat_dismiss_snackbar"
        static let chatOverflowRename = "chat_overflow_rename"
        static let chatOverflowReviewChanges = "chat_overflow_review_changes"
        static let chatOverflowSessionContext = "chat_overflow_session_context"
        static let chatOverflowCopySessionId = "chat_overflow_copy_session_id"
        static let chatOverflowArchive = "chat_overflow_archive"
        static let chatOverflowDelete = "chat_overflow_delete"

        static let composerOpenModelPicker = "composer_open_model_picker"
        static let composerRetryModelCatalog = "composer_retry_model_catalog"
        static let composerOpenModePicker = "composer_open_mode_picker"
        static let composerOpenVariantPicker = "composer_open_variant_picker"
        static let composerOpenCommands = "composer_open_commands"
        static let composerAttach = "composer_attach"
        static let composerRemoveAttachment = "composer_remove_attachment"
        static let composerSend = "composer_send"
        static let composerAbort = "composer_abort"
        static let composerSelectSlashSuggestion = "composer_select_slash_suggestion"
        static let composerSelectAtSuggestion = "composer_select_at_suggestion"
        static let composerDismissSuggestions = "composer_dismiss_suggestions"

        static let permissionAllowOnce = "permission_allow_once"
        static let permissionDeny = "permission_deny"
        static let questionToggleOption = "question_toggle_option"
        static let questionToggleCustom = "question_toggle_custom"
        static let questionEditCustomText = "question_edit_custom_text"
        static let questionBack = "question_back"
        static let questionNext = "question_next"
        static let questionSubmit = "question_submit"
        static let questionDismiss = "question_dismiss"

        static let commandsDismiss = "commands_dismiss"
        static let commandsNew = "commands_new"
        static let commandsFork = "commands_fork"
        static let commandsUndo = "commands_undo"
        static let commandsRedo = "commands_redo"
        static let commandsCompact = "commands_compact"
        static let commandsTerminal = "commands_terminal"
        static let commandsMcp = "commands_mcp"
        static let mcpServersBack = "mcp_servers_back"
        static let attachSourceDismiss = "attach_source_dismiss"
        static let attachPickPhotos = "attach_pick_photos"
        static let attachPickFiles = "attach_pick_files"
        static let attachPickLogFile = "attach_pick_log_file"
        static let modelPickerDismiss = "model_picker_dismiss"
        static let modelPickerSelectProvider = "model_picker_select_provider"
        static let modelPickerBackToProviders = "model_picker_back_to_providers"
        static let modelPickerFilter = "model_picker_filter"
        static let modelPickerSelectModel = "model_picker_select_model"
        static let modePickerDismiss = "mode_picker_dismiss"
        static let modePickerSelect = "mode_picker_select"
        static let variantPickerDismiss = "variant_picker_dismiss"
        static let variantPickerSelect = "variant_picker_select"
        static let chatRenameEditTitle = "chat_rename_edit_title"
        static let chatRenameSave = "chat_rename_save"
        static let chatRenameCancel = "chat_rename_cancel"
        static let chatForkEditTitle = "chat_fork_edit_title"
        static let chatForkConfirm = "chat_fork_confirm"
        static let chatForkCancel = "chat_fork_cancel"
        static let chatForkOpen = "chat_fork_open"
        static let chatForkStay = "chat_fork_stay"
        static let chatDeleteConfirm = "chat_delete_confirm"
        static let chatDeleteCancel = "chat_delete_cancel"
        static let messageActionsDismiss = "message_actions_dismiss"
        static let messageActionsRevert = "message_actions_revert"
        static let messageActionsCopy = "message_actions_copy"
        static let chatSteerPendingPrompt = "chat_steer_pending_prompt"
        static let chatQueuePendingPrompt = "chat_queue_pending_prompt"
        static let chatCancelPendingPrompt = "chat_cancel_pending_prompt"
        static let pendingPromptsDismiss = "pending_prompts_dismiss"
        static let pendingPromptsSteer = "pending_prompts_steer"
        static let pendingPromptsQueue = "pending_prompts_queue"
        static let pendingPromptsDelete = "pending_prompts_delete"
        static let pendingPromptsClear = "pending_prompts_clear"
        static let toolDetailDismiss = "tool_detail_dismiss"
        static let sessionToolGroupBack = "session_tool_group_back"
        static let todosDismiss = "todos_dismiss"

        static let sessionContextBack = "session_context_back"
        static let sessionContextRefresh = "session_context_refresh"
        static let sessionContextRetry = "session_context_retry"
        static let changesBack = "changes_back"
        static let changesOpenSourceMenu = "changes_open_source_menu"
        static let changesSelectSource = "changes_select_source"
        static let changesRefresh = "changes_refresh"
        static let changesRetry = "changes_retry"
        static let changesUpdateFilter = "changes_update_filter"
        static let changesClearFilter = "changes_clear_filter"
        static let changesOpenFile = "changes_open_file"
        static let fileViewerBack = "file_viewer_back"
        static let fileViewerHunkPrevious = "file_viewer_hunk_previous"
        static let fileViewerHunkNext = "file_viewer_hunk_next"
        static let fileViewerToggleContext = "file_viewer_toggle_context"

        static let settingsBack = "settings_back"
        static let settingsOpenConnection = "settings_open_connection"
        static let settingsOpenAppearance = "settings_open_appearance"
        static let settingsOpenThemePicker = "settings_open_theme_picker"
        static let settingsOpenSyntaxPicker = "settings_open_syntax_picker"
        static let settingsToggleHideUserDirectory = "settings_toggle_hide_user_directory"
        static let settingsOpenRecentWindowPicker = "settings_open_recent_window_picker"
        static let settingsToggleCollapseConsecutiveToolCalls = "settings_toggle_collapse_consecutive_tool_calls"
        static let settingsToggleSmoothStreaming = "settings_toggle_smooth_streaming"
        static let settingsToggleSessionListProjectPicker = "settings_toggle_session_list_project_picker"
        static let settingsClearSessionCache = "settings_clear_session_cache"
        static let settingsOpenPrivacyPolicy = "settings_open_privacy_policy"
        static let settingsToggleProductAnalytics = "settings_toggle_product_analytics"
        static let settingsClearAllData = "settings_clear_all_data"
        static let themePickerDismiss = "theme_picker_dismiss"
        static let themePickerSelectPreset = "theme_picker_select_preset"
        static let syntaxPickerSelectPreset = "syntax_picker_select_preset"
        static let recentWindowDismiss = "recent_window_dismiss"
        static let recentWindowSelectHours = "recent_window_select_hours"
        static let clearCacheConfirm = "clear_cache_confirm"
        static let clearCacheCancel = "clear_cache_cancel"
        static let clearAllDataConfirm = "clear_all_data_confirm"
        static let clearAllDataCancel = "clear_all_data_cancel"
    }

    enum Param {
        static let screenId = "screen_id"
        static let actionId = "action_id"
        static let presentation = "presentation"
        static let trigger = "trigger"
        static let outcome = "outcome"
        static let mode = "mode"
        static let changeSource = "change_source"
        static let clientSlash = "client_slash"
        static let permissionDecision = "permission_decision"
        static let questionResponse = "question_response"
        static let hours = "hours"
        static let themePreset = "theme_preset"
        static let syntaxPreset = "syntax_preset"
        static let hasAttachments = "has_attachments"
        static let isEdit = "is_edit"
        static let errorClass = "error_class"
    }

    static func logScreenView(
        screenId: String,
        presentation: String,
        trigger: String = Trigger.push
    ) {
        OpenCodeReleaseObservability.logProductEvent(
            name: Event.screenView,
            parameters: [
                Param.screenId: screenId,
                Param.presentation: presentation,
                Param.trigger: trigger,
            ]
        )
    }

    static func logUserAction(
        screenId: String,
        actionId: String,
        parameters: [String: String] = [:]
    ) {
        var merged = parameters
        merged[Param.screenId] = screenId
        merged[Param.actionId] = actionId
        OpenCodeReleaseObservability.logProductEvent(
            name: Event.userAction,
            parameters: merged
        )
    }
}
