package com.david.tvcontinue;

import org.junit.Test;
import static org.junit.Assert.*;

public final class YouTubeMetadataTest {
    @Test public void missingValuesStayExplicitlyUnknown() {
        YouTubeMetadata m = metadata(null, null, null, null, null, null);
        assertEquals("", m.channel);
        assertEquals("", m.releaseDate);
        assertEquals("", m.publishedLabel);
        assertEquals(-1, m.durationMillis);
        assertEquals(-1, m.viewCount);
        assertEquals(-1, m.live);
    }

    @Test public void channelRemovesControlsAndCollapsesSpacing() {
        assertEquals("Studio Name", metadata(" \tStudio\u202E\n  Name\u2069 ", "", "", "", "", "").channel);
        assertEquals("", metadata("\n\t\u202E\u2066", "", "", "", "", "").channel);
    }

    @Test public void channelBoundDoesNotSplitSupplementaryCharacters() {
        String emoji = "\uD83C\uDFB5";
        String value = String.join("", java.util.Collections.nCopies(150, emoji));
        String actual = metadata(value, "", "", "", "", "").channel;
        assertEquals(128, actual.codePointCount(0, actual.length()));
        assertTrue(actual.endsWith(emoji));
    }

    @Test public void completeCalendarDatesAreStrictlyValidated() {
        assertEquals("2024-02-29", metadata("", " 2024-02-29 ", "", "", "", "").releaseDate);
        for (String bad : new String[]{"2025-02-29", "2025-04-31", "2025-13-01", "2025-00-01", "0000-01-01"})
            assertEquals(bad, "", metadata("", bad, "", "", "", "").releaseDate);
    }

    @Test public void partialDatesNeverBecomeInventedFullDates() {
        for (String partial : new String[]{"2025", "2025-01", "25-01-01", "20250101", "2025-1-1", "10000-01-01"})
            assertEquals(partial, "", metadata("", partial, "", "", "", "").releaseDate);
    }

    @Test public void OffsetDatesAreValidatedAndNormalizedToUtcDay() {
        assertEquals("2025-11-06", metadata("", "2025-11-06T14:35:20Z", "", "", "", "").releaseDate);
        assertEquals("2025-11-06", metadata("", "2025-11-07T00:30:00+02:00", "", "", "", "").releaseDate);
        assertEquals("2025-11-07", metadata("", "2025-11-06T23:30:00-02:00", "", "", "", "").releaseDate);
        for (String bad : new String[]{"2025-11-06T25:00:00Z", "2025-11-06T14:35:20", "2025-11-06\nT14:35:20Z"})
            assertEquals(bad, "", metadata("", bad, "", "", "", "").releaseDate);
    }

    @Test public void durationRequiresPositiveWholeMilliseconds() {
        assertEquals(123456, metadata("", "", " 123456 ", "", "", "").durationMillis);
        for (String invalid : new String[]{"", "0", "-1", "1.5", "NaN", "1e3", "+50", "9223372036854775808"})
            assertEquals(invalid, -1, metadata("", "", invalid, "", "", "").durationMillis);
    }

    @Test public void onlyAnExplicitViewsInteractionBecomesViewCount() {
        assertEquals(42, metadata("", "", "", "0", "42", "").viewCount);
        assertEquals(0, metadata("", "", "", "0", "0", "").viewCount);
        for (String differentType : new String[]{null, "", "-1", "1", "2", "3", "4", "5", "6", "99", "0.0"})
            assertEquals(String.valueOf(differentType), -1, metadata("", "", "", differentType, "42", "").viewCount);
    }

    @Test public void viewCountRejectsMalformedAndOverflowValues() {
        assertEquals(Long.MAX_VALUE, metadata("", "", "", "0", "9223372036854775807", "").viewCount);
        for (String bad : new String[]{null, "", "-2", "4.2", "9223372036854775808", "3 million"})
            assertEquals(String.valueOf(bad), -1, metadata("", "", "", "0", bad, "").viewCount);
    }

    @Test public void liveIsTriStateAndOnlyAcceptsProviderBooleans() {
        assertEquals(0, metadata("", "", "", "", "", "0").live);
        assertEquals(1, metadata("", "", "", "", "", "1").live);
        for (String bad : new String[]{null, "", "true", "false", "2", "-1", "1.0", "9223372036854775808"})
            assertEquals(String.valueOf(bad), -1, metadata("", "", "", "", "", bad).live);
    }

    @Test public void badOptionalFieldsDoNotRemoveIndependentUsableMetadata() {
        YouTubeMetadata m = metadata("Channel", "not-a-date", "90000", "4", "500", "1");
        assertEquals("Channel", m.channel);
        assertEquals("", m.releaseDate);
        assertEquals(90000, m.durationMillis);
        assertEquals(-1, m.viewCount);
        assertEquals(1, m.live);
    }

    @Test public void verifiedEnglishPreviewShapeSuppliesChannelAndLiteralRecency() {
        for (String recency : new String[]{"2 months ago", "7 hours ago", "1 hour ago", "Streamed 3 days ago", "Premiered 1 year ago"}) {
            YouTubeMetadata m = fromDescription("", "Magic Baby Sleep • " + recency);
            assertEquals("Magic Baby Sleep", m.channel);
            assertEquals(recency, m.publishedLabel);
            assertEquals("", m.releaseDate);
        }
        assertEquals("Маница Хашба", fromDescription("", "Маница Хашба • 1 hour ago").channel);
    }

    @Test public void frenchRecencyAndMiddleDotArePreservedWithoutAnInventedDate() {
        YouTubeMetadata m = fromDescription("", "  Chaîne française\u00A0·\u00A0Il y a 2 mois  ");
        assertEquals("Chaîne française", m.channel);
        assertEquals("Il y a 2 mois", m.publishedLabel);
        assertEquals("", m.releaseDate);
        assertEquals("Diffusé en direct il y a 3 heures",
                fromDescription("", "Créateur · Diffusé en direct il y a 3 heures").publishedLabel);
    }

    @Test public void explicitAuthorWinsOverDescriptionChannel() {
        YouTubeMetadata m = fromDescription("Provided Author", "Other Name • 7 hours ago");
        assertEquals("Provided Author", m.channel);
        assertEquals("7 hours ago", m.publishedLabel);
    }

    @Test public void lastSeparatorSupportsRecencyWithoutCallingMultipleLeftBlocksAChannel() {
        YouTubeMetadata m = fromDescription("", "Channel • 1.2M views • 2 days ago");
        assertEquals("2 days ago", m.publishedLabel);
        assertEquals("", m.channel);
        assertEquals("Provided", fromDescription("Provided", "Channel · 1.2M views • 2 days ago").channel);
    }

    @Test public void unknownCompoundDescriptionsNeverClaimPublicationOrChannel() {
        for (String value : new String[]{"Channel • LIVE", "Channel · upcoming", "Channel • 2025-01-02",
                "Channel • New this week", "Channel • 5K views", "Channel • 2 days ago (approx.)"}) {
            YouTubeMetadata m = fromDescription("", value);
            assertEquals(value, "", m.channel);
            assertEquals(value, "", m.publishedLabel);
        }
        assertEquals("Known", fromDescription("Known", "Channel • unrecognized").channel);
    }

    @Test public void onlyPlausibleSimpleDescriptionsBecomeAChannelFallback() {
        assertEquals("Plain Channel", fromVideoDescription("Plain Channel").channel);
        for (String value : new String[]{"", "LIVE", "Mix", "2 hours ago", "500 views", "1,000", "https://example.com"}) {
            YouTubeMetadata m = fromVideoDescription(value);
            assertEquals(value, "", m.channel);
            assertEquals(value, "", m.publishedLabel);
        }
    }

    @Test public void noSeparatorNeedsEvidenceOfARegularVideoOrAnActualLiveStream() {
        YouTubeMetadata adultSwim = YouTubeMetadata.from("", "", "623000", "0", "3", "0", "Adult Swim");
        assertEquals("Adult Swim", adultSwim.channel);
        assertEquals("", adultSwim.publishedLabel);
        assertEquals("", fromDescription("", "Adult Swim").channel);
        assertEquals("", YouTubeMetadata.from("", "", "623000", "4", "3", "0", "Adult Swim").channel);
        assertEquals("", YouTubeMetadata.from("", "", "623000", "0", "", "0", "Adult Swim").channel);
        assertEquals("Channel", YouTubeMetadata.from("", "", "", "", "", "1", "Channel").channel);
    }

    @Test public void actualMixAndMovieDescriptionsAreNotMislabelledAsChannels() {
        for (String value : new String[]{"Personalized mix for you", "DJ Premier, Skrillex, Pretty Lights, and more",
                "Маница Хашба, Other Artist, and more", "The story of two people who meet on an unexpected adventure."}) {
            YouTubeMetadata m = fromDescription("", value);
            assertEquals(value, "", m.channel);
            assertEquals(value, "", m.publishedLabel);
        }
        assertEquals("", fromVideoDescription("Personalized mix for you").channel);
        assertEquals("", fromVideoDescription("DJ Premier, Skrillex, Pretty Lights, and more").channel);
    }

    @Test public void aCompletedStreamHasLiteralRecencyWithoutBecomingLive() {
        YouTubeMetadata m = YouTubeMetadata.from("", "", "25576000", "0", "2356059", "0",
                "LoL Esports • Streamed 17 hours ago");
        assertEquals("LoL Esports", m.channel);
        assertEquals("Streamed 17 hours ago", m.publishedLabel);
        assertEquals("", m.releaseDate);
        assertEquals(0, m.live);
        assertEquals(2356059, m.viewCount);
    }

    @Test public void descriptionControlsAndExcessiveValuesCannotLeakIntoLabels() {
        YouTubeMetadata m = fromDescription("", "\u202EChannel\u2069 • 2\n hours ago");
        assertEquals("Channel", m.channel);
        assertEquals("2 hours ago", m.publishedLabel);
        String tooLong = String.join("", java.util.Collections.nCopies(2200, "a")) + " • 2 hours ago";
        assertEquals("", fromDescription("", tooLong).channel);
        assertEquals("", fromDescription("", tooLong).publishedLabel);
    }

    private static YouTubeMetadata fromDescription(String author, String description) {
        return YouTubeMetadata.from(author, "", "", "", "", "", description);
    }

    private static YouTubeMetadata fromVideoDescription(String description) {
        return YouTubeMetadata.from("", "", "60000", "0", "3", "0", description);
    }

    private static YouTubeMetadata metadata(String channel, String date, String duration,
                                            String type, String count, String live) {
        return YouTubeMetadata.from(channel, date, duration, type, count, live);
    }
}
