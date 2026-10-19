package app.vitune.android.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyCsvTest {
    @Test fun likedExportsStayInFavorites() {
        assertEquals(null, spotifyPlaylistName("liked.csv"))
        assertEquals(null, spotifyPlaylistName("LIKED.CSV"))
        assertEquals(null, spotifyPlaylistName("liked (1).csv"))
        assertEquals(null, spotifyPlaylistName("/Download/liked.csv"))
    }

    @Test fun eachOtherFileUsesItsPlaylistName() {
        assertEquals("Treino", spotifyPlaylistName("Treino.csv"))
        assertEquals("Noite, verão", spotifyPlaylistName("Noite, verão.CSV"))
        assertEquals("Rock", spotifyPlaylistName("C:\\Download\\Rock.csv"))
        assertEquals("liked remixes", spotifyPlaylistName("liked remixes.csv"))
        assertEquals(listOf(null, "Treino", "Rock"), listOf("liked.csv", "Treino.csv", "Rock.csv").map(::spotifyPlaylistName))
    }

    @Test fun quotesUnicodeDuplicatesAndInvalidRows() {
        val input = "\uFEFFTrack URI,Track Name,Artist Name(s),Duration (ms)\r\n" +
            "spotify:track:1,\"Olá, \"\"mundo\"\"\",Björk;Other,180000\r\n" +
            "spotify:track:1,duplicate,Björk,180000\r\n" +
            "spotify:track:2,\"Line one\nLine two\",POLI,200000\r\n" +
            "spotify:track:3,,No title,200000\r\n"
        val result = parseSpotifyCsv(input)
        assertEquals(2, result.tracks.size)
        assertEquals("Olá, \"mundo\"", result.tracks[0].title)
        assertEquals("Björk;Other", result.tracks[0].artists)
        assertEquals(180000L, result.tracks[0].durationMs)
        assertEquals("Line one\nLine two", result.tracks[1].title)
        assertEquals(1, result.duplicates)
        assertEquals(1, result.invalid)
    }

    @Test fun portugueseExportifyHeaders() {
        val input = "\"URI da faixa\",\"Nome da faixa\",\"URI(s) do artista\",\"Nome(s) do artista\",\"Duração da faixa (ms)\"\n" +
            "spotify:track:example,Canção,spotify:artist:example,Artista,180000\n" +
            "spotify:track:example,Outro título,spotify:artist:example,Artista,180000\n"
        val parsed = parseSpotifyCsv(input)
        assertEquals(1, parsed.tracks.size)
        assertEquals("Canção", parsed.tracks[0].title)
        assertEquals("Artista", parsed.tracks[0].artists)
        assertEquals(180000L, parsed.tracks[0].durationMs)
        assertEquals(1, parsed.duplicates)
    }

    @Test fun semicolonExportAndDuplicateWithoutUri() {
        val csv = "Track Name;Artist Name(s);Duration (ms)\nCanção;Artista;100000\nCanção;Artista;100000\n"
        assertEquals(1, parseSpotifyCsv(csv).tracks.size)
        assertEquals(1, parseSpotifyCsv(csv).duplicates)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsWrongFile() {
        parseSpotifyCsv("name,email\nAlice,alice@example.com")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnclosedQuotes() {
        parseSpotifyCsv("Track Name,Artist Name(s)\n\"Broken,Artist")
    }

    @Test fun matchRequiresArtistVersionAndDuration() {
        val track = SpotifyTrack("Canção", "Björk;Other", 180000)
        assertTrue(spotifyMatchScore(track, "Cancao", listOf("Bjork"), 180) >= 0.88)
        assertEquals(0.0, spotifyMatchScore(track, "Canção", listOf("Other artist"), 180), 0.0)
        assertEquals(0.0, spotifyMatchScore(track, "Canção live", listOf("Björk"), 180), 0.0)
        assertEquals(0.0, spotifyMatchScore(track, "Canção", listOf("Björk"), 300), 0.0)
        assertEquals(0.0, spotifyMatchScore(track, "Different song", listOf("Björk"), 180), 0.0)
    }
}
