package com.david.tvcontinue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class YouTubePreviewPolicyTest {
    private static final String OWNER = "com.google.android.youtube.tv";

    @Test public void onlyExactYouTubeOwnerAndUsableDisplayFieldsAreAccepted() {
        List<YouTubePreviewPolicy.Row> rows = Arrays.asList(
                row(OWNER, "Video", "https://www.youtube.com/watch?v=first", "", 1, 1),
                row(OWNER + ".fake", "Video", "fake-owner", "image", 1, 2),
                row("com.google.android.youtube", "Video", "phone-owner", "image", 1, 3),
                row("com.netflix.ninja", "Video", "other-app", "image", 1, 4),
                row(OWNER, " \t", "blank-title", "image", 1, 5),
                row(OWNER, "Video", " \n", "image", 1, 6));

        List<YouTubePreviewPolicy.Row> selected = YouTubePreviewPolicy.select(rows, 60);

        assertEquals(OWNER, YouTubePreviewPolicy.OWNER);
        assertEquals(1, selected.size());
        assertEquals("https://www.youtube.com/watch?v=first", selected.get(0).intent);
        // Missing artwork remains eligible for the existing application-icon fallback.
        assertEquals("", selected.get(0).poster);
    }

    @Test public void oversizedIntentsAreDroppedWithoutDroppingTheBoundaryValue() {
        String accepted = repeated('a', 16384);
        String rejected = accepted + 'a';

        List<YouTubePreviewPolicy.Row> selected = YouTubePreviewPolicy.select(Arrays.asList(
                row(OWNER, "Too long", rejected, "", 1, 1),
                row(OWNER, "Within limit", accepted, "", 1, 2)), 60);

        assertEquals(1, selected.size());
        assertEquals(accepted, selected.get(0).intent);
    }

    @Test public void duplicatesDoNotConsumeTheVisibleLimit() {
        List<YouTubePreviewPolicy.Row> selected = YouTubePreviewPolicy.select(Arrays.asList(
                row(OWNER, "First copy", "video-a", "art-a", 1, 1),
                row(OWNER, "Second copy", "video-a", "art-a", 1, 2),
                row(OWNER, "Video B", "video-b", "art-b", 1, 3),
                row(OWNER, "Video C", "video-c", "art-c", 1, 4)), 2);

        assertEquals(Arrays.asList("video-a", "video-b"), intents(selected));
        assertEquals("First copy", selected.get(0).title);
    }

    @Test public void unreadableTitleCannotDisplaceAValidDuplicate() {
        List<YouTubePreviewPolicy.Row> selected = YouTubePreviewPolicy.select(Arrays.asList(
                row(OWNER, "\u202E\u2066\u2069", "video-a", "art-a", 1, 1),
                row(OWNER, "Readable title", "video-a", "art-a", 1, 2)), 1);

        assertEquals(1, selected.size());
        assertEquals("Readable title", selected.get(0).title);
        assertEquals("video-a", selected.get(0).intent);
    }

    @Test public void displayedTitlesRemoveControlsAndAreBounded() {
        assertEquals("", YouTubePreviewPolicy.displayTitle(null));
        assertEquals("", YouTubePreviewPolicy.displayTitle("\n\u202E\u2066\u2069\t"));
        assertEquals("Alpha Beta Gamma", YouTubePreviewPolicy.displayTitle("  Alpha\u202EBeta\nGamma  "));
        assertEquals(repeated('a', 512), YouTubePreviewPolicy.displayTitle(repeated('a', 700)));
    }

    @Test public void providerRowOrderDoesNotChangeSelectionOrDuplicateWinner() {
        List<YouTubePreviewPolicy.Row> rows = new ArrayList<>(Arrays.asList(
                row(OWNER, "Video B", "video-b", "art-b", 2, 2),
                row(OWNER, "Video C", "video-c", "art-c", 1, 10),
                row(OWNER, "Later duplicate", "video-a", "other-art", 4, 1),
                row(OWNER, "Video A", "video-a", "art-a", 1, 9)));
        List<YouTubePreviewPolicy.Row> first = YouTubePreviewPolicy.select(rows, 60);
        Collections.reverse(rows);
        List<YouTubePreviewPolicy.Row> reversed = YouTubePreviewPolicy.select(rows, 60);
        Collections.rotate(rows, 1);
        List<YouTubePreviewPolicy.Row> rotated = YouTubePreviewPolicy.select(rows, 60);

        assertEquals(Arrays.asList("video-a", "video-c", "video-b"), intents(first));
        assertEquals(intents(first), intents(reversed));
        assertEquals(intents(first), intents(rotated));
        assertEquals("Video A", first.get(0).title);
        assertEquals(first.get(0).title, reversed.get(0).title);
        assertEquals(first.get(0).poster, rotated.get(0).poster);
    }

    @Test public void equalProviderCoordinatesHaveDeterministicTieBreakers() {
        List<YouTubePreviewPolicy.Row> rows = new ArrayList<>(Arrays.asList(
                row(OWNER, "Zulu", "video-a", "z-art", 1, 1),
                row(OWNER, "Alpha", "video-a", "b-art", 1, 1),
                row(OWNER, "Alpha", "video-a", "a-art", 1, 1),
                row(OWNER, "Before title", "video-b", "art", 1, 1)));
        List<YouTubePreviewPolicy.Row> first = YouTubePreviewPolicy.select(rows, 60);
        Collections.reverse(rows);
        List<YouTubePreviewPolicy.Row> reversed = YouTubePreviewPolicy.select(rows, 60);

        assertEquals(Arrays.asList("video-a", "video-b"), intents(first));
        assertEquals("Alpha", first.get(0).title);
        assertEquals("a-art", first.get(0).poster);
        assertEquals(intents(first), intents(reversed));
        assertEquals(first.get(0).poster, reversed.get(0).poster);
    }

    @Test public void identityUsesExactLaunchIntentRatherThanTitleOrProviderRowId() {
        YouTubePreviewPolicy.Row first = row(OWNER, "Title one", "video-a", "art-one", 1, 1);
        YouTubePreviewPolicy.Row duplicate = row(OWNER, "Title two", "video-a", "art-two", 8, 55);
        YouTubePreviewPolicy.Row differentLink = row(OWNER, "Title one", "video-a?source=other", "art-one", 1, 1);

        assertEquals(first.identity(), duplicate.identity());
        assertNotEquals(first.identity(), differentLink.identity());
    }

    @Test public void optionalMetadataTravelsWithTheExistingStableDuplicateWinner() {
        YouTubeMetadata original = YouTubeMetadata.from("Channel A", "2025-01-02", "90000", "0", "123", "0");
        YouTubeMetadata duplicate = YouTubeMetadata.from("Other channel", "2025-01-03", "90000", "0", "124", "1");
        YouTubePreviewPolicy.Row first = new YouTubePreviewPolicy.Row(OWNER, "Video", "video", "art", 1, 1, original);
        YouTubePreviewPolicy.Row later = new YouTubePreviewPolicy.Row(OWNER, "Video", "video", "art", 2, 2, duplicate);
        List<YouTubePreviewPolicy.Row> selected = YouTubePreviewPolicy.select(Arrays.asList(later, first), 60);
        assertEquals(1, selected.size());
        assertSame(original, selected.get(0).metadata);
        assertSame(YouTubeMetadata.EMPTY, row(OWNER, "Legacy", "legacy", "art", 1, 3).metadata);
    }

    @Test public void requestedLimitBoundsTheResult() {
        List<YouTubePreviewPolicy.Row> rows = new ArrayList<>();
        for (int i = 0; i < 80; i++) rows.add(row(OWNER, "Video " + i, "video-" + i, "", 1, i));

        assertEquals(60, YouTubePreviewPolicy.select(rows, 60).size());
        assertEquals(4, YouTubePreviewPolicy.select(rows, 4).size());
        assertTrue(YouTubePreviewPolicy.select(rows, 0).isEmpty());
    }

    private static YouTubePreviewPolicy.Row row(String owner, String title, String intent,
                                               String poster, long channel, long id) {
        return new YouTubePreviewPolicy.Row(owner, title, intent, poster, channel, id);
    }

    private static List<String> intents(List<YouTubePreviewPolicy.Row> rows) {
        List<String> result = new ArrayList<>();
        for (YouTubePreviewPolicy.Row row : rows) result.add(row.intent);
        return result;
    }

    private static String repeated(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
