package com.tropicalstream.hammerklavier.contract

// Producers emit codes; WP10's UiText owns every word and every priority.

enum class StatusCode { CRASH_LAST_SESSION, IMPORT_FAILED, IMPORT_PERMISSION, SCORES_DIR_FOREIGN, FALLBACK, AUDIO_UNAVAILABLE,
                        AUDIO_STOPPED, DISPLAY_REST, IMPORTED, NEW_BT_DEVICE }
enum class RejectReason { NOT_MIDI, TRUNCATED, BAD_HEADER, TOO_LARGE, TOO_MANY_EVENTS, NO_KEYBOARD_NOTES, DUPLICATE,
                          ZIP_LIMIT, ZIP_TRAVERSAL, PERMISSION_DENIED, IO_ERROR }
enum class FallbackReason { BANK_MISSING, DECODER_UNAVAILABLE, PROBE_FAILED, LOW_STORAGE }
enum class PerfWarning { HANGING_NOTES, FORMAT2_SEQUENTIAL, TRUNCATED_CHUNK, TRAILING_JUNK, RUNNING_STATUS_REPAIRED,
                         DRUMS_DROPPED, CHANNELS_MERGED, FOLDED, FINGER_PEDALLED }

/** One active status line entry. Priority comes from UiText: lower number = higher priority. */
class StatusItem(val code: StatusCode, val args: List<String>, val untilMs: Long)
