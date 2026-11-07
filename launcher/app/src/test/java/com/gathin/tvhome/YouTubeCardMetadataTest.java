package com.gathin.tvhome;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.*;

public class YouTubeCardMetadataTest {
    @Test public void unknownMetadataDoesNotBecomeAnEmptyRowOrZeroViews() {
        assertArrayEquals(new String[0], YouTubeCardMetadata.EMPTY.lines(Locale.US));
        assertEquals("", YouTubeCardMetadata.EMPTY.badge());
        assertEquals("", YouTubeCardMetadata.EMPTY.accessibility(Locale.US));
    }

    @Test public void separatesChannelFromDetailsSoEachLineCanBeTruncatedIndependently() {
        YouTubeCardMetadata metadata = new YouTubeCardMetadata("A channel", "2026-11-06", 90_000, 125_000, false);
        assertArrayEquals(new String[]{"A channel", "6 Nov 2026 · 125K views"}, metadata.lines(Locale.US));
        assertEquals("1:30", metadata.badge());
        assertTrue(metadata.accessibility(Locale.US).contains("Duration 1:30"));
    }

    @Test public void compactsMissingChannelOrDetailsWithoutBlankSeparators() {
        assertArrayEquals(new String[]{"2026 · 0 views"},
                new YouTubeCardMetadata("", "2026", -1, 0, false).lines(Locale.US));
        assertArrayEquals(new String[]{"A channel"},
                new YouTubeCardMetadata("A channel", "", -1, -1, false).lines(Locale.US));
    }

    @Test public void datePrecisionIsPreservedAndUtcTimestampHasAnExplicitCalendarDate() {
        assertEquals("2026", YouTubeCardMetadata.publication("2026", Locale.US));
        assertEquals("Nov 2026", YouTubeCardMetadata.publication("2026-11", Locale.US));
        assertEquals("6 Nov 2026", YouTubeCardMetadata.publication("2026-11-06", Locale.US));
        assertEquals("6 Nov 2026", YouTubeCardMetadata.publication("2026-11-06T00:01:00Z", Locale.US));
    }

    @Test public void literalYouTubePublicationLabelIsShownWithoutInventingAnExactDate() {
        YouTubeCardMetadata relative = new YouTubeCardMetadata("Magic Baby Sleep", "", "2 months ago", -1, 125_000, false);
        assertArrayEquals(new String[]{"Magic Baby Sleep", "2 months ago · 125K views"}, relative.lines(Locale.US));
        assertEquals("", relative.publishedDate);
        YouTubeCardMetadata exact = new YouTubeCardMetadata("Channel", "2026-11-06", "7 hours ago", -1, -1, false);
        assertArrayEquals(new String[]{"Channel", "6 Nov 2026"}, exact.lines(Locale.US));
    }

    @Test public void invalidDatesRelativeLabelsAndEngagementTimestampsAreNeverInventedDates() {
        for (String invalid : new String[]{"", "0000", "0000-01", "0000-01-01", "2026-02-30", "2026-13", "1794018905949", "3 days ago", "yesterday", "2026-11-06TbadZ"})
            assertEquals(invalid, "", YouTubeCardMetadata.publication(invalid, Locale.US));
    }

    @Test public void durationHandlesHoursAndRejectsAbsentOrImplausiblyLargeValues() {
        assertEquals("0:59", YouTubeCardMetadata.duration(59_999));
        assertEquals("1:30", YouTubeCardMetadata.duration(90_000));
        assertEquals("1:02:03", YouTubeCardMetadata.duration(3_723_000));
        for (long missing : new long[]{-1, 0, 1, Long.MAX_VALUE})
            assertEquals("", YouTubeCardMetadata.duration(missing));
    }

    @Test public void liveBadgeTakesPriorityOverStreamDuration() {
        YouTubeCardMetadata live = new YouTubeCardMetadata("Channel", "", 30_000_000, -1, true);
        assertEquals("LIVE", live.badge());
        assertTrue(live.accessibility(Locale.US).contains("Live"));
        assertFalse(live.accessibility(Locale.US).contains("Duration"));
    }

    @Test public void viewsAreCompactLocalizedAndNeverRoundedUpToTheNextMagnitude() {
        assertEquals("", YouTubeCardMetadata.views(-1, Locale.US));
        assertEquals("0 views", YouTubeCardMetadata.views(0, Locale.US));
        assertEquals("1 view", YouTubeCardMetadata.views(1, Locale.US));
        assertEquals("1 vue", YouTubeCardMetadata.views(1, Locale.FRANCE));
        assertEquals("999 views", YouTubeCardMetadata.views(999, Locale.US));
        assertEquals("1.2K views", YouTubeCardMetadata.views(1_234, Locale.US));
        assertEquals("999.9K views", YouTubeCardMetadata.views(999_999, Locale.US));
        assertEquals("1.2M views", YouTubeCardMetadata.views(1_234_567, Locale.US));
        assertEquals("1,2K vues", YouTubeCardMetadata.views(1_234, Locale.FRANCE));
        assertEquals("", YouTubeCardMetadata.views(Long.MAX_VALUE, Locale.US));
    }

    @Test public void untrustedLabelsCannotAddRowsOrDirectionOverrides() {
        YouTubeCardMetadata metadata = new YouTubeCardMetadata(" Channel\nname\u202E ", " 2026 ", -1, -1, false);
        assertEquals("Channel name", metadata.channel);
        assertEquals("2026", metadata.publishedDate);
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) longName.append('a');
        assertEquals(120, new YouTubeCardMetadata(longName.toString(), "", -1, -1, false).channel.length());
        StringBuilder unicodeName = new StringBuilder();
        for (int i = 0; i < 130; i++) unicodeName.appendCodePoint(0x1D11E);
        String shortened = new YouTubeCardMetadata(unicodeName.toString(), "", -1, -1, false).channel;
        assertEquals(120, shortened.codePointCount(0, shortened.length()));
        assertEquals(0x1D11E, shortened.codePointBefore(shortened.length()));
    }
}
