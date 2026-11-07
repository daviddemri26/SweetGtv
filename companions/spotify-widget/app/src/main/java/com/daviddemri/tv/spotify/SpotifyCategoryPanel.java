package com.daviddemri.tv.spotify;

/** Owns category expansion. Only an explicit activation can open or replace its content. */
final class SpotifyCategoryPanel {
    static final int NONE = 0, OPEN = 1, CLOSE = 2, REPLACE = 3;
    private static final String[] CATEGORIES = {"recent", "playlists", "resume"};
    private int activeIndex = -1;

    /** Reports the explicit transition so a caller can load content only for OPEN/REPLACE. */
    int click(String category) {
        int index = indexOf(category);
        if (index < 0) return NONE;
        int previous = activeIndex;
        activate(index);
        return activeIndex < 0 ? CLOSE : previous < 0 ? OPEN : REPLACE;
    }

    /** Returns false for an invalid index without changing the current expansion. */
    boolean activate(int index) {
        if (index < 0 || index >= CATEGORIES.length) return false;
        activeIndex = activeIndex == index ? -1 : index;
        return true;
    }

    int activeIndex() { return activeIndex; }
    boolean isOpen() { return activeIndex >= 0; }
    boolean isExpanded() { return isOpen(); }
    boolean isActive(int index) { return isOpen() && activeIndex == index; }
    boolean isActive(String category) { return isActive(indexOf(category)); }
    boolean shows(String renderedCategory) { return isActive(renderedCategory); }
    String category() { return isOpen() ? CATEGORIES[activeIndex] : ""; }

    /** Sign-out/lifecycle closure cannot be undone by catalog responses or focus changes. */
    void close() { activeIndex = -1; }

    private static int indexOf(String category) {
        for (int index = 0; index < CATEGORIES.length; index++) if (CATEGORIES[index].equals(category)) return index;
        return -1;
    }
}
