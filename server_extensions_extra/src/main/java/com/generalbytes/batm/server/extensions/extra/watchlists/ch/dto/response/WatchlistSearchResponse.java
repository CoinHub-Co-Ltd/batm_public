package com.generalbytes.batm.server.extensions.extra.watchlists.ch.dto.response;

import java.util.List;

public class WatchlistSearchResponse {
    public List<Match> matches;
    public static class Match {
        public String partyId;
        public int score;
        public String details;
        public String source; 
    }
}

