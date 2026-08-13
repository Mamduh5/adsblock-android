package com.example.siteshield

enum class BrowserUiMode {
    NORMAL,
    READER,
}

fun browserUiModeFor(pageType: PageType): BrowserUiMode =
    when (pageType) {
        PageType.CHAPTER_READER -> BrowserUiMode.READER
        PageType.HOME_LIST_SEARCH,
        PageType.DETAIL,
        PageType.UNKNOWN,
        -> BrowserUiMode.NORMAL
    }
