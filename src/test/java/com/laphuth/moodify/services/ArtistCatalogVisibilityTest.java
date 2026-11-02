package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArtistCatalogVisibilityTest {
    @Test void archivedTracksDoNotAppearOnPublicArtistPage() {
        ArtistRepository artists=mock(ArtistRepository.class);
        ArtistProfileRepository profiles=mock(ArtistProfileRepository.class);
        ArtistReviewRepository reviews=mock(ArtistReviewRepository.class);
        FavoriteArtistRepository favorites=mock(FavoriteArtistRepository.class);
        UserRepository users=mock(UserRepository.class);
        TrackRepository tracks=mock(TrackRepository.class);
        AlbumRepository mongoAlbums=mock(AlbumRepository.class);
        AlbumEntityRepository albums=mock(AlbumEntityRepository.class);
        AlbumTrackRepository albumTracks=mock(AlbumTrackRepository.class);
        SongLicenseRepository licenses=mock(SongLicenseRepository.class);
        Artist artist=new Artist();artist.setSpotifyId("artist");artist.setName("Artist");
        Track active=new Track();active.setId("active");active.setName("New");active.setStatus("published");
        Track old=new Track();old.setId("old");old.setName("Old");old.setStatus("archived");
        when(artists.findBySpotifyId("artist")).thenReturn(Optional.of(artist));
        when(tracks.findByArtistSpotifyId("artist")).thenReturn(List.of(active,old));
        when(tracks.findCatalogPublic(any())).thenReturn(new PageImpl<>(List.of(active)));
        when(licenses.findIneligibleTrackIds()).thenReturn(List.of());
        ArtistService service=new ArtistService(artists,profiles,reviews,favorites,users,tracks,mongoAlbums,albums,albumTracks,new TrackService(tracks,licenses));
        var response=service.getPublicProfile("artist",null);
        assertEquals(1,response.topTracks().size());
        assertEquals("active",response.topTracks().get(0).id());
    }
}
