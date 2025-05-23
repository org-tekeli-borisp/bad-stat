package de.ostfale.qk.data.player.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

public class FavoritePlayerListData {

    Logger log = Logger.getLogger(FavoritePlayerListData.class);

    private final List<FavoritePlayerData> favoritePlayersList = new ArrayList<>();

    public List<FavoritePlayerData> favoritePlayersList() {
        return favoritePlayersList;
    }

    public List<FavoritePlayerData> getFavoritePlayersList() {
        return favoritePlayersList;
    }

    @JsonIgnore
    public boolean doesPlayerExist(String playerName) {
        return favoritePlayersList.stream().anyMatch(favoritePlayerData -> favoritePlayerData.getName().equalsIgnoreCase(playerName));
    }

    @JsonIgnore
    public void addPlayerCustomData(FavoritePlayerData favoritePlayerData) {
        log.debugf("PlayerCustomDataListHandler :: addPlayerCustomData(%s)", favoritePlayerData.getPlayerId());
        favoritePlayersList.add(favoritePlayerData);
    }
}
