# Telegram Media Streaming, Stall Recovery, & Download Rules

## 1. TDLib Download Stall Recovery
- When chunk fetching in `TelegramStreamingProxy` stalls (`attempts % 100 == 0`), simply calling `triggerTdlibDownload` with the same offset is ignored by TDLib because `isOffsetJump` calculates as `false`.
- **Requirement**: Always invoke `TdApi.CancelDownloadFile(activeFileId, false)` and clear `lastDownloadRequestOffset` when a stall is detected. This forces TDLib to clear stuck DC worker threads and establish a fresh network request to Telegram servers.

## 2. Multi-Part Video Grouping & Zero-Byte Filtering
- When grouping split video parts in `TelegramRepository.groupSplitFiles`:
  - Never reconcile non-part single files into an existing split group if `part1` (`hasPart1 == true`) is already present.
  - Always filter out `fileSize <= 0` or invalid messages before forming `SplitFileGroup`.
- In Watch History (`loadWatchHistory` & `showHistoryGroupFilesPicker`):
  - Exclude `/merged/` stream URLs, `(Combined)` titles, and `0 MB` items from being mapped as individual video parts.

## 3. Action Scoping (Streaming vs. Downloading)
- When the user triggers a **Download** action on multi-part media (`handleDownloadItem` or `downloadStreamSource` in download mode), route the request to `showGroupDownloadOptionsDialog` (where items bind to `DownloadManager.startDownload`).
- Route to `showGroupPartsSelectionDialog` only when the user intends to **Stream/Play** media via ExoPlayer.
