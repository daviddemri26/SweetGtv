package com.david.tvcontinue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Preview ordering is stable provider order, never an inferred viewing history. */
final class YouTubePreviewPolicy {
    static final String OWNER = "com.google.android.youtube.tv";
    static final class Row {
        final String owner, title, intent, poster;
        final long channelId, rowId;
        final YouTubeMetadata metadata;
        Row(String owner, String title, String intent, String poster, long channelId, long rowId) {
            this(owner, title, intent, poster, channelId, rowId, YouTubeMetadata.EMPTY);
        }
        Row(String owner, String title, String intent, String poster, long channelId, long rowId,
            YouTubeMetadata metadata) {
            this.owner = owner;
            this.title = title;
            this.intent = intent;
            this.poster = poster == null ? "" : poster;
            this.channelId = channelId;
            this.rowId = rowId;
            this.metadata = metadata == null ? YouTubeMetadata.EMPTY : metadata;
        }
        String identity() { return intent; }
    }

    static List<Row> select(List<Row> rows, int limit) {
        List<Row> sorted = new ArrayList<>();
        if (limit <= 0) return sorted;
        for (Row row : rows) {
            if (row != null && OWNER.equals(row.owner) && !displayTitle(row.title).isEmpty()
                    && row.intent != null && !row.intent.trim().isEmpty() && row.intent.length() <= 16384)
                sorted.add(row);
        }
        sorted.sort(Comparator.comparingLong((Row row) -> row.channelId).thenComparingLong(row -> row.rowId)
                .thenComparing(row -> row.intent).thenComparing(row -> row.title).thenComparing(row -> row.poster));
        Set<String> seen = new HashSet<>();
        List<Row> selected = new ArrayList<>();
        for (Row row : sorted) {
            if (!seen.add(row.identity())) continue;
            selected.add(row);
            if (selected.size() == limit) break;
        }
        return selected;
    }

    static String displayTitle(String title) {
        if (title == null) return "";
        String cleaned = title.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", " ").trim();
        return cleaned.length() > 512 ? cleaned.substring(0, 512) : cleaned;
    }
}
