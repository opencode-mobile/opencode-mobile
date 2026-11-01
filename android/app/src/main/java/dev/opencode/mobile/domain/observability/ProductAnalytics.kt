package dev.opencode.mobile

/**
 * OPE-124 product Analytics taxonomy. Feature / Screen Model code calls these
 * helpers only — never Firebase. All sends go through
 * [OpenCodeReleaseObservability.logProductEvent] (consent + Debug gate).
 */
object ProductAnalytics {
    private val suppressions = java.util.concurrent.atomic.AtomicInteger(0)

    /** Where events go; tests swap it to see what would be sent. */
    internal var sink: (name: String, parameters: Map<String, String>) -> Unit =
        { name, parameters -> OpenCodeReleaseObservability.logProductEvent(name, parameters) }

    /** True while a [suppress] handle is open: nothing is logged. */
    val isSuppressed: Boolean
        get() = suppressions.get() > 0

    /** Open [suppress] handles; tests compare it before and after. */
    internal val suppressionCount: Int
        get() = suppressions.get()

    /**
     * Stops every product event until the returned handle closes. The Session Chat Playground
     * holds one while it is open: its fake sessions are not product use.
     */
    fun suppress(): AutoCloseable {
        suppressions.incrementAndGet()
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) suppressions.decrementAndGet() }
    }

    object Event {
        const val SCREEN_VIEW = "product_screen_view"
        const val USER_ACTION = "product_user_action"
    }

    object Presentation {
        const val ROUTE = "route"
        const val SHEET = "sheet"
        const val DIALOG = "dialog"
        const val OVERLAY = "overlay"
        const val EMBEDDED = "embedded"
        const val FULLSCREEN = "fullscreen"
    }

    object Trigger {
        const val PUSH = "push"
        const val PRESENT = "present"
        const val RESUME = "resume"
    }

    /** Allowed [Param.OUTCOME] values. Enum-only, never a server message. */
    object Outcome {
        const val SUCCESS = "success"
        const val FAILURE = "failure"
        const val BLOCKED = "blocked"
    }

    object Screen {
        const val APP_LOADING = "app_loading"
        const val APP_ONBOARDING = "app_onboarding"
        const val APP_ONBOARDING_SETUP_GUIDE = "app_onboarding_setup_guide"
        const val HOME = "home"
        const val HOME_CONNECTION_EMPTY = "home_connection_empty"
        const val HOME_PROBE_FAILED = "home_probe_failed"
        const val CONNECTION_SETUP = "connection_setup"
        const val PAIRING_QR_SCANNER = "pairing_qr_scanner"
        const val HOME_ADD_PROJECT = "home_add_project"
        const val HOME_NEW_SESSION_PROJECT_PICKER = "home_new_session_project_picker"
        const val HOME_FILTER_PROJECT_PICKER = "home_filter_project_picker"
        const val HOME_SESSION_ACTIONS = "home_session_actions"
        const val HOME_RENAME_SESSION = "home_rename_session"
        const val HOME_FORK_SESSION = "home_fork_session"
        const val HOME_FORK_RESULT = "home_fork_result"
        const val HOME_DELETE_SESSION_CONFIRM = "home_delete_session_confirm"
        const val HOME_REMOVE_PROJECT_CONFIRM = "home_remove_project_confirm"
        const val TERMINAL = "terminal"
        const val TERMINALS_CATALOG = "terminals_catalog"
        const val SESSION_CHAT = "session_chat"
        const val SESSION_OVERFLOW = "session_overflow"
        const val SESSION_COMMANDS = "session_commands"
        const val SESSION_RENAME = "session_rename"
        const val SESSION_FORK = "session_fork"
        const val SESSION_FORK_RESULT = "session_fork_result"
        const val SESSION_DELETE_CONFIRM = "session_delete_confirm"
        const val SESSION_ATTACHMENT_SOURCE = "session_attachment_source"
        const val SESSION_MODEL_PICKER = "session_model_picker"
        const val SESSION_MODE_PICKER = "session_mode_picker"
        const val SESSION_VARIANT_PICKER = "session_variant_picker"
        const val SESSION_MESSAGE_ACTIONS = "session_message_actions"
        const val SESSION_PENDING_PROMPTS = "session_pending_prompts"
        const val SESSION_TOOL_DETAIL = "session_tool_detail"
        const val SESSION_TOOL_GROUP = "session_tool_group"
        const val SESSION_SKILLS = "session_skills"
        const val SESSION_CHIP_TIMELINE_INTRO = "session_chip_timeline_intro"
        const val SESSION_TIMELINE_STYLE_PICKER = "session_timeline_style_picker"
        const val SESSION_TODOS = "session_todos"
        const val SESSION_SUBAGENTS = "session_subagents"
        const val SESSION_SHELL_OUTPUT = "session_shell_output"
        const val SESSION_CONTEXT = "session_context"
        const val CHANGES = "changes"
        const val CHANGES_FILE_VIEWER = "changes_file_viewer"
        const val FILES = "files"
        const val FILES_VIEWER = "files_viewer"
        const val SETTINGS = "settings"
        const val SETTINGS_APPEARANCE = "settings_appearance"
        const val SETTINGS_THEME_PICKER = "settings_theme_picker"
        const val SETTINGS_SYNTAX_PICKER = "settings_syntax_picker"
        const val SETTINGS_RECENT_WINDOW_PICKER = "settings_recent_window_picker"
        const val SETTINGS_CLEAR_CACHE_CONFIRM = "settings_clear_cache_confirm"
        const val SETTINGS_CLEAR_ALL_DATA_CONFIRM = "settings_clear_all_data_confirm"
    }

    object Action {
        // Onboarding
        const val ONBOARDING_SKIP = "onboarding_skip"
        const val ONBOARDING_NEXT = "onboarding_next"
        const val ONBOARDING_CONNECT = "onboarding_connect"
        const val ONBOARDING_OPEN_SETUP_GUIDE = "onboarding_open_setup_guide"
        const val ONBOARDING_SWIPE_PAGE = "onboarding_swipe_page"
        const val SETUP_GUIDE_BACK = "setup_guide_back"
        const val SETUP_GUIDE_OPEN_DOCS = "setup_guide_open_docs"

        // Home
        const val HOME_OPEN_SETTINGS = "home_open_settings"
        const val HOME_OPEN_TERMINAL = "home_open_terminal"
        const val HOME_PULL_TO_REFRESH = "home_pull_to_refresh"
        const val HOME_OPEN_SESSION = "home_open_session"
        const val HOME_LONG_PRESS_SESSION = "home_long_press_session"
        const val HOME_CREATE_SESSION = "home_create_session"
        const val HOME_OPEN_NEW_SESSION_PROJECT_PICKER = "home_open_new_session_project_picker"
        const val HOME_OPEN_PROJECT_PICKER = "home_open_project_picker"
        const val HOME_ADD_PROJECT = "home_add_project"
        const val HOME_REMOVE_PROJECT = "home_remove_project"
        const val HOME_REORDER_PROJECTS = "home_reorder_projects"
        const val HOME_SELECT_SESSION_FILTER = "home_select_session_filter"
        const val HOME_DISMISS_REMOVAL_SNACKBAR = "home_dismiss_removal_snackbar"
        const val HOME_CONNECT = "home_connect"
        const val HOME_RETRY_PROBE = "home_retry_probe"
        const val HOME_RETRY_PROJECT_LOAD = "home_retry_project_load"
        const val HOME_EDIT_CONNECTION = "home_edit_connection"

        // Connection
        const val CONNECTION_BACK_CANCEL = "connection_back_cancel"
        const val CONNECTION_EDIT_BASE_URL = "connection_edit_base_url"
        const val CONNECTION_EDIT_USERNAME = "connection_edit_username"
        const val CONNECTION_EDIT_PASSWORD = "connection_edit_password"
        const val CONNECTION_TEST = "connection_test"
        const val CONNECTION_SAVE = "connection_save"
        const val CONNECTION_OPEN_PAIRING_QR = "connection_open_pairing_qr"
        const val CONNECTION_PAIRING_QR_SCAN = "connection_pairing_qr_scan"

        // Add project / see all / picker
        const val ADD_PROJECT_DISMISS = "add_project_dismiss"
        const val ADD_PROJECT_GO_UP = "add_project_go_up"
        const val ADD_PROJECT_ENTER_DIRECTORY = "add_project_enter_directory"
        const val ADD_PROJECT_SELECT_CURRENT = "add_project_select_current"
        const val ADD_PROJECT_RETRY_NAVIGATOR = "add_project_retry_navigator"
        const val ADD_PROJECT_SELECT_ROOT = "add_project_select_root"
        const val ADD_PROJECT_SELECT_SUGGESTION = "add_project_select_suggestion"
        const val ADD_PROJECT_BROWSE = "add_project_browse"
        const val ADD_PROJECT_BROWSE_BACK = "add_project_browse_back"
        const val NEW_SESSION_PICKER_DISMISS = "new_session_picker_dismiss"
        const val PROJECT_PICKER_DISMISS = "project_picker_dismiss"
        const val NEW_SESSION_PICKER_SELECT_PROJECT = "new_session_picker_select_project"

        // Home session actions
        const val SESSION_ACTIONS_DISMISS = "session_actions_dismiss"
        const val SESSION_ACTIONS_RENAME = "session_actions_rename"
        const val SESSION_ACTIONS_FORK = "session_actions_fork"
        const val SESSION_ACTIONS_ARCHIVE = "session_actions_archive"
        const val SESSION_ACTIONS_DELETE = "session_actions_delete"
        const val HOME_RENAME_EDIT_TITLE = "home_rename_edit_title"
        const val HOME_RENAME_SAVE = "home_rename_save"
        const val HOME_RENAME_CANCEL = "home_rename_cancel"
        const val HOME_FORK_EDIT_TITLE = "home_fork_edit_title"
        const val HOME_FORK_CONFIRM = "home_fork_confirm"
        const val HOME_FORK_CANCEL = "home_fork_cancel"
        const val HOME_FORK_OPEN = "home_fork_open"
        const val HOME_FORK_STAY = "home_fork_stay"
        const val HOME_DELETE_CONFIRM = "home_delete_confirm"
        const val HOME_DELETE_CANCEL = "home_delete_cancel"
        const val HOME_REMOVE_PROJECT_CONFIRM = "home_remove_project_confirm"
        const val HOME_REMOVE_PROJECT_CANCEL = "home_remove_project_cancel"

        // Chat chrome
        const val CHAT_BACK = "chat_back"
        const val CHAT_OPEN_OVERFLOW = "chat_open_overflow"
        const val CHAT_OPEN_SESSION_CONTEXT = "chat_open_session_context"
        const val CHAT_OPEN_CHANGES = "chat_open_changes"
        const val CHAT_OPEN_CHANGES_FROM_TOOL = "chat_open_changes_from_tool"
        const val CHAT_OPEN_CHILD_SESSION = "chat_open_child_session"
        const val CHAT_OPEN_TODOS = "chat_open_todos"
        const val CHAT_OPEN_SUBAGENTS = "chat_open_subagents"
        const val CHAT_SCROLL_TO_END = "chat_scroll_to_end"
        const val CHAT_OPEN_MESSAGE_ACTIONS = "chat_open_message_actions"
        const val CHAT_OPEN_TOOL_DETAIL = "chat_open_tool_detail"
        const val CHAT_OPEN_TOOL_GROUP = "chat_open_tool_group"
        const val CHAT_OPEN_SKILLS = "chat_open_skills"
        const val CHAT_DISMISS_SNACKBAR = "chat_dismiss_snackbar"
        const val CHAT_OVERFLOW_RENAME = "chat_overflow_rename"
        const val CHAT_OVERFLOW_REVIEW_CHANGES = "chat_overflow_review_changes"
        const val CHAT_OVERFLOW_FORK = "chat_overflow_fork"
        const val CHAT_OVERFLOW_MOVE_TO_WORKTREE = "chat_overflow_move_to_worktree"
        const val CHAT_OVERFLOW_BROWSE_FILES = "chat_overflow_browse_files"
        const val CHAT_OVERFLOW_SESSION_CONTEXT = "chat_overflow_session_context"
        const val CHAT_OVERFLOW_COPY_SESSION_ID = "chat_overflow_copy_session_id"
        const val CHAT_OVERFLOW_ARCHIVE = "chat_overflow_archive"
        const val CHAT_OVERFLOW_DELETE = "chat_overflow_delete"

        // Composer
        const val COMPOSER_OPEN_MODEL_PICKER = "composer_open_model_picker"
        const val COMPOSER_RETRY_MODEL_CATALOG = "composer_retry_model_catalog"
        const val COMPOSER_OPEN_MODE_PICKER = "composer_open_mode_picker"
        const val COMPOSER_OPEN_VARIANT_PICKER = "composer_open_variant_picker"
        const val COMPOSER_OPEN_COMMANDS = "composer_open_commands"
        const val COMPOSER_ATTACH = "composer_attach"
        const val COMPOSER_REMOVE_ATTACHMENT = "composer_remove_attachment"
        const val COMPOSER_SEND = "composer_send"
        const val COMPOSER_ABORT = "composer_abort"
        const val COMPOSER_SELECT_SLASH_SUGGESTION = "composer_select_slash_suggestion"
        const val COMPOSER_SELECT_AT_SUGGESTION = "composer_select_at_suggestion"
        const val COMPOSER_DISMISS_SUGGESTIONS = "composer_dismiss_suggestions"

        // Permission / question
        const val PERMISSION_ALLOW_ONCE = "permission_allow_once"
        const val PERMISSION_DENY = "permission_deny"
        const val PERMISSION_ALWAYS = "permission_always"
        const val PERMISSION_HIDE = "permission_hide"
        const val PERMISSION_SHOW = "permission_show"
        const val QUESTION_TOGGLE_OPTION = "question_toggle_option"
        const val QUESTION_TOGGLE_CUSTOM = "question_toggle_custom"
        const val QUESTION_EDIT_CUSTOM_TEXT = "question_edit_custom_text"
        const val QUESTION_BACK = "question_back"
        const val QUESTION_NEXT = "question_next"
        const val QUESTION_SUBMIT = "question_submit"
        const val QUESTION_DISMISS = "question_dismiss"
        const val QUESTION_HIDE = "question_hide"
        const val QUESTION_SHOW = "question_show"
        const val INBOX_REQUEST_PAGE = "inbox_request_page"

        // Commands / pickers / dialogs
        const val COMMANDS_DISMISS = "commands_dismiss"
        const val COMMANDS_NEW = "commands_new"
        const val COMMANDS_FORK = "commands_fork"
        const val COMMANDS_UNDO = "commands_undo"
        const val COMMANDS_REDO = "commands_redo"
        const val COMMANDS_COMPACT = "commands_compact"
        const val COMMANDS_TERMINAL = "commands_terminal"
        const val COMMANDS_FILES = "commands_files"
        const val COMMANDS_MCP = "commands_mcp"
        const val COMMANDS_BTW = "commands_btw"
        const val SIDE_QUESTION_ASK = "side_question_ask"
        const val SIDE_QUESTION_OPEN_ANSWER = "side_question_open_answer"
        const val SIDE_QUESTION_COPY = "side_question_copy"
        const val SIDE_QUESTION_CLOSE = "side_question_close"
        const val SIDE_QUESTION_RETRY = "side_question_retry"
        const val SIDE_QUESTION_DISMISS_FAILED = "side_question_dismiss_failed"
        const val MCP_SERVERS_BACK = "mcp_servers_back"
        const val ATTACH_SOURCE_DISMISS = "attach_source_dismiss"
        const val ATTACH_PICK_PHOTOS = "attach_pick_photos"
        const val ATTACH_PICK_FILES = "attach_pick_files"
        const val ATTACH_PICK_LOG_FILE = "attach_pick_log_file"
        const val ATTACH_PASTE = "attach_paste"
        const val ATTACH_DROP = "attach_drop"
        const val MODEL_PICKER_DISMISS = "model_picker_dismiss"
        const val MODEL_PICKER_SELECT_PROVIDER = "model_picker_select_provider"
        const val MODEL_PICKER_BACK_TO_PROVIDERS = "model_picker_back_to_providers"
        const val MODEL_PICKER_FILTER = "model_picker_filter"
        const val MODEL_PICKER_SELECT_MODEL = "model_picker_select_model"
        const val MODE_PICKER_DISMISS = "mode_picker_dismiss"
        const val MODE_PICKER_SELECT = "mode_picker_select"
        const val VARIANT_PICKER_DISMISS = "variant_picker_dismiss"
        const val VARIANT_PICKER_SELECT = "variant_picker_select"
        const val CHAT_RENAME_EDIT_TITLE = "chat_rename_edit_title"
        const val CHAT_RENAME_SAVE = "chat_rename_save"
        const val CHAT_RENAME_CANCEL = "chat_rename_cancel"
        const val CHAT_FORK_EDIT_TITLE = "chat_fork_edit_title"
        const val CHAT_FORK_CONFIRM = "chat_fork_confirm"
        const val CHAT_FORK_CANCEL = "chat_fork_cancel"
        const val CHAT_FORK_OPEN = "chat_fork_open"
        const val CHAT_FORK_STAY = "chat_fork_stay"
        const val CHAT_DELETE_CONFIRM = "chat_delete_confirm"
        const val CHAT_DELETE_CANCEL = "chat_delete_cancel"
        const val MESSAGE_ACTIONS_DISMISS = "message_actions_dismiss"
        const val MESSAGE_ACTIONS_REVERT = "message_actions_revert"
        const val MESSAGE_ACTIONS_FORK = "message_actions_fork"
        const val MESSAGE_ACTIONS_COPY = "message_actions_copy"
        const val MESSAGE_ACTIONS_COPY_CODE_BLOCK = "message_actions_copy_code_block"
        const val CHAT_STEER_PENDING_PROMPT = "chat_steer_pending_prompt"
        const val CHAT_QUEUE_PENDING_PROMPT = "chat_queue_pending_prompt"
        const val CHAT_CANCEL_PENDING_PROMPT = "chat_cancel_pending_prompt"
        const val PENDING_PROMPTS_DISMISS = "pending_prompts_dismiss"
        const val PENDING_PROMPTS_STEER = "pending_prompts_steer"
        const val PENDING_PROMPTS_QUEUE = "pending_prompts_queue"
        const val PENDING_PROMPTS_DELETE = "pending_prompts_delete"
        const val PENDING_PROMPTS_CLEAR = "pending_prompts_clear"
        const val TOOL_DETAIL_DISMISS = "tool_detail_dismiss"
        const val SKILLS_DISMISS = "skills_dismiss"
        const val CHIP_TIMELINE_INTRO_ENABLE = "chip_timeline_intro_enable"
        const val CHIP_TIMELINE_INTRO_LATER = "chip_timeline_intro_later"
        const val CHIP_TIMELINE_INTRO_NEVER = "chip_timeline_intro_never"
        const val TIMELINE_STYLE_PICKER_USE = "timeline_style_picker_use"
        const val TIMELINE_STYLE_PICKER_DISMISS = "timeline_style_picker_dismiss"
        const val SESSION_TOOL_GROUP_BACK = "session_tool_group_back"
        const val TODOS_DISMISS = "todos_dismiss"
        const val SUBAGENTS_DISMISS = "subagents_dismiss"
        const val SHELL_OUTPUT_OPEN = "shell_output_open"
        const val SHELL_OUTPUT_DISMISS = "shell_output_dismiss"
        const val SHELL_OUTPUT_COPY = "shell_output_copy"
        const val SHELL_OUTPUT_STOP = "shell_output_stop"

        // Session context / changes
        const val SESSION_CONTEXT_BACK = "session_context_back"
        const val SESSION_CONTEXT_REFRESH = "session_context_refresh"
        const val SESSION_CONTEXT_RETRY = "session_context_retry"
        const val CHANGES_BACK = "changes_back"
        const val CHANGES_OPEN_SOURCE_MENU = "changes_open_source_menu"
        const val CHANGES_SELECT_SOURCE = "changes_select_source"
        const val CHANGES_REFRESH = "changes_refresh"
        const val CHANGES_RETRY = "changes_retry"
        const val CHANGES_UPDATE_FILTER = "changes_update_filter"
        const val CHANGES_CLEAR_FILTER = "changes_clear_filter"
        const val CHANGES_OPEN_FILE = "changes_open_file"
        const val FILE_VIEWER_BACK = "file_viewer_back"
        const val FILE_VIEWER_HUNK_PREVIOUS = "file_viewer_hunk_previous"
        const val FILE_VIEWER_HUNK_NEXT = "file_viewer_hunk_next"
        const val FILE_VIEWER_TOGGLE_CONTEXT = "file_viewer_toggle_context"

        // File Browser
        const val FILES_BACK = "files_back"
        const val FILES_TOGGLE_FOLDER = "files_toggle_folder"
        const val FILES_OPEN_FILE = "files_open_file"
        const val FILES_UPDATE_QUERY = "files_update_query"
        const val FILES_TOGGLE_HIDDEN = "files_toggle_hidden"
        const val FILES_REFRESH = "files_refresh"
        const val FILES_RETRY = "files_retry"
        const val FILES_SELECT_TAB = "files_select_tab"
        const val FILES_CLOSE_TAB = "files_close_tab"
        const val FILES_VIEWER_BACK = "files_viewer_back"
        const val FILES_VIEWER_COPY_PATH = "files_viewer_copy_path"
        const val FILES_VIEWER_TOGGLE_WRAP = "files_viewer_toggle_wrap"
        const val FILES_VIEWER_TOGGLE_MARKDOWN_SOURCE = "files_viewer_toggle_markdown_source"
        const val FILES_VIEWER_RETRY = "files_viewer_retry"

        // Settings
        const val SETTINGS_BACK = "settings_back"
        const val SETTINGS_OPEN_CONNECTION = "settings_open_connection"
        const val SETTINGS_OPEN_APPEARANCE = "settings_open_appearance"
        const val SETTINGS_OPEN_THEME_PICKER = "settings_open_theme_picker"
        const val SETTINGS_OPEN_SYNTAX_PICKER = "settings_open_syntax_picker"
        const val SETTINGS_TOGGLE_HIDE_USER_DIRECTORY = "settings_toggle_hide_user_directory"
        const val SETTINGS_TOGGLE_WORKTREES = "settings_toggle_worktrees"
        const val SETTINGS_OPEN_SESSION_ROWS_PICKER = "settings_open_session_rows_picker"
        const val SETTINGS_SESSION_ROWS = "settings_session_rows"
        const val SETTINGS_OPEN_RECENT_WINDOW_PICKER = "settings_open_recent_window_picker"
        const val SETTINGS_TOGGLE_COLLAPSE_CONSECUTIVE_TOOL_CALLS = "settings_toggle_collapse_consecutive_tool_calls"
        const val SETTINGS_TOGGLE_CHIP_TIMELINE = "settings_toggle_chip_timeline"
        const val SETTINGS_CLEAR_SESSION_CACHE = "settings_clear_session_cache"
        const val SETTINGS_OPEN_PRIVACY_POLICY = "settings_open_privacy_policy"
        const val SETTINGS_TOGGLE_PRODUCT_ANALYTICS = "settings_toggle_product_analytics"
        const val SETTINGS_TOGGLE_APP_LOCK = "settings_toggle_app_lock"
        const val SETTINGS_CLEAR_ALL_DATA = "settings_clear_all_data"
        const val THEME_PICKER_DISMISS = "theme_picker_dismiss"
        const val THEME_PICKER_SELECT_PRESET = "theme_picker_select_preset"
        const val SYNTAX_PICKER_SELECT_PRESET = "syntax_picker_select_preset"
        const val RECENT_WINDOW_DISMISS = "recent_window_dismiss"
        const val RECENT_WINDOW_SELECT_HOURS = "recent_window_select_hours"
        const val CLEAR_CACHE_CONFIRM = "clear_cache_confirm"
        const val CLEAR_CACHE_CANCEL = "clear_cache_cancel"
        const val CLEAR_ALL_DATA_CONFIRM = "clear_all_data_confirm"
        const val CLEAR_ALL_DATA_CANCEL = "clear_all_data_cancel"

        // Tablet split view
        const val SPLIT_PANE_TOGGLE_SIDEBAR = "split_pane_toggle_sidebar"
    }

    object Param {
        const val SCREEN_ID = "screen_id"
        const val ACTION_ID = "action_id"
        const val PRESENTATION = "presentation"
        const val TRIGGER = "trigger"
        const val OUTCOME = "outcome"
        const val MODE = "mode"
        const val CHANGE_SOURCE = "change_source"
        const val CLIENT_SLASH = "client_slash"
        const val PERMISSION_DECISION = "permission_decision"
        const val QUESTION_RESPONSE = "question_response"
        const val HOURS = "hours"
        const val THEME_PRESET = "theme_preset"
        const val SYNTAX_PRESET = "syntax_preset"
        const val HAS_ATTACHMENTS = "has_attachments"
        const val IS_EDIT = "is_edit"
        const val ERROR_CLASS = "error_class"
    }

    fun logScreenView(
        screenId: String,
        presentation: String,
        trigger: String = Trigger.PUSH,
    ) {
        if (isSuppressed) return
        sink(
            Event.SCREEN_VIEW,
            mapOf(
                Param.SCREEN_ID to screenId,
                Param.PRESENTATION to presentation,
                Param.TRIGGER to trigger,
            ),
        )
    }

    fun logUserAction(
        screenId: String,
        actionId: String,
        parameters: Map<String, String> = emptyMap(),
    ) {
        if (isSuppressed) return
        sink(
            Event.USER_ACTION,
            buildMap {
                put(Param.SCREEN_ID, screenId)
                put(Param.ACTION_ID, actionId)
                putAll(parameters)
            },
        )
    }
}
