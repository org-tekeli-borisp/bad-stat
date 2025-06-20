package de.ostfale.qk.ui.playerstats.info;

import de.ostfale.qk.data.dashboard.RankingPlayerCacheHandler;
import de.ostfale.qk.data.player.model.FavPlayerData;
import de.ostfale.qk.domain.player.Player;
import de.ostfale.qk.domain.player.PlayerId;
import de.ostfale.qk.domain.player.PlayerTournamentId;
import de.ostfale.qk.ui.playerstats.info.masterdata.PlayerInfoDTO;
import de.ostfale.qk.ui.playerstats.info.rankingdata.PlayerDiscStatDTO;
import de.ostfale.qk.ui.playerstats.info.tournamentdata.PlayerTourStatDTO;
import de.ostfale.qk.ui.playerstats.matches.PlayerInfoMatchStatService;
import de.ostfale.qk.web.async.PlayerAsyncWebService;
import de.ostfale.qk.web.player.PlayerWebParserService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

@ApplicationScoped
public class PlayerInfoService {

    @Inject
    RankingPlayerCacheHandler rankingPlayerCacheHandler;

    @Inject
    PlayerInfoMatchStatService playerInfoMatchStatService;

    @Inject
    PlayerWebParserService playerWebParserService;

    @Inject
    PlayerAsyncWebService playerAsyncWebService;

    private final Map<PlayerId, PlayerInfoDTO> playerInfoDTOMap = new ConcurrentHashMap<>();

    public PlayerInfoDTO getPlayerInfoDTO(FavPlayerData favPlayerData) {
        Log.debugf("PlayerInfoService :: Read player infos for favourite player %s", favPlayerData.playerName());
        if (playerInfoDTOMap.containsKey(favPlayerData.playerId())) {
            Log.debugf("PlayerInfoService :: found player info in cache for player id %s", favPlayerData.playerId());
            return playerInfoDTOMap.get(favPlayerData.playerId());
        }
        Player foundPlayer = rankingPlayerCacheHandler.getRankingPlayerCache().getPlayerByPlayerId(favPlayerData.playerId().playerId());
        var playerInfo = new PlayerInfoDTO(foundPlayer);
        playerInfo.setSingleDiscStat(mapSingleDisciplineStatistics(foundPlayer));
        playerInfo.setDoubleDiscStat(mapDoubleDisciplineStatistics(foundPlayer));
        playerInfo.setMixedDiscStat(mapMixedDisciplineStatistics(foundPlayer));
        playerInfo.getPlayerInfoMasterDataDTO().setPlayerTournamentId(favPlayerData.playerTournamentId().tournamentId());
        playerInfo.setPlayerTourStatDTO(new PlayerTourStatDTO(favPlayerData));
        return playerInfo;
    }

    public PlayerInfoDTO getPlayerInfoDTO(PlayerId playerIdObject) {
        String playerId = playerIdObject.playerId();
        Log.debugf("PlayerInfoService :: get player info for player id %s", playerIdObject.playerId());
        if (playerInfoDTOMap.containsKey(playerIdObject)) {
            Log.debugf("PlayerInfoService :: found player info in cache for player id %s", playerIdObject.playerId());
            return playerInfoDTOMap.get(playerIdObject);
        }
        Player foundPlayer = rankingPlayerCacheHandler.getRankingPlayerCache().getPlayerByPlayerId(playerId);
        var playerInfo = new PlayerInfoDTO(foundPlayer);
        playerInfo.setSingleDiscStat(mapSingleDisciplineStatistics(foundPlayer));
        playerInfo.setDoubleDiscStat(mapDoubleDisciplineStatistics(foundPlayer));
        playerInfo.setMixedDiscStat(mapMixedDisciplineStatistics(foundPlayer));

        playerAsyncWebService.fetchPlayerTournamentId(playerId)
                .onFailure().invoke(throwable -> Log.errorf("Failed to get tournament id for player %s", playerId, throwable))
                .onItem().transform(PlayerTournamentId::tournamentId)
                .subscribe().with(tournamentId -> {
                    playerInfo.getPlayerInfoMasterDataDTO().setPlayerTournamentId(tournamentId);
                });

        var playerIdObj = new PlayerId(playerId);
        var playerTournamentId = new PlayerTournamentId(playerInfo.getPlayerInfoMasterDataDTO().getPlayerTournamentId());
        playerAsyncWebService.fetchPlayerTourStatInfo(playerIdObj, playerTournamentId)
                .onFailure().invoke(throwable -> Log.errorf("Failed to get tournament statistics for player %s", playerId, throwable))
                .subscribe().with(playerInfo::setPlayerTourStatDTO);

        playerInfoDTOMap.put(playerIdObject, playerInfo);
        return playerInfo;
    }

    public List<PlayerInfoDTO> getPlayerInfoList() {
        Log.debug("PlayerInfoService :: map all players from cache into PlayerInfoDTOs ");
        var rankingPlayerCache = rankingPlayerCacheHandler.getRankingPlayerCache();
        if (rankingPlayerCache != null) {
            var result = rankingPlayerCache.players().stream().map(PlayerInfoDTO::new).toList();
            Log.debugf("Found %d players in cache", result.size());
            return result;
        }
        return List.of();
    }

    public PlayerInfoDTO getPlayerInfosForPlayerName(String playerName) {
        List<Player> foundPlayers = rankingPlayerCacheHandler.getRankingPlayerCache().getPlayerByName(playerName);
        if (foundPlayers.size() == 1) {
            return getPlayerInfoDTO(foundPlayers.getFirst().getPlayerId());
        }
        Log.errorf("Multiple players found with name: %s -> %d", playerName, foundPlayers.size());
        return null;
    }

    private PlayerDiscStatDTO mapSingleDisciplineStatistics(Player player) {
        return Optional.ofNullable(player.getSingleRankingInformation())
                .map(info -> new PlayerDiscStatDTO(
                        info.tournaments(),
                        info.rankingPoints(),
                        info.rankingPosition(),
                        calculatePlayersRanking(player, Player::getSinglePoints, "single")))
                .orElse(new PlayerDiscStatDTO(0, 0, 0, 0));
    }

    private PlayerDiscStatDTO mapDoubleDisciplineStatistics(Player player) {
        return Optional.ofNullable(player.getDoubleRankingInformation())
                .map(info -> new PlayerDiscStatDTO(
                        info.tournaments(),
                        info.rankingPoints(),
                        info.rankingPosition(),
                        calculatePlayersRanking(player, Player::getDoublePoints, "double")))
                .orElse(new PlayerDiscStatDTO(0, 0, 0, 0));
    }

    private PlayerDiscStatDTO mapMixedDisciplineStatistics(Player player) {
        return Optional.ofNullable(player.getMixedRankingInformation())
                .map(info -> new PlayerDiscStatDTO(
                        info.tournaments(),
                        info.rankingPoints(),
                        info.rankingPosition(),
                        calculatePlayersRanking(player, Player::getMixedPoints, "mixed")))
                .orElse(new PlayerDiscStatDTO(0, 0, 0, 0));
    }

    private Integer calculatePlayersRanking(Player player, ToIntFunction<Player> pointsExtractor, String rankingType) {
        Log.debugf("PlayerInfoService :: calculate ranking for player %s", player.getFullName());
        List<Player> filteredPlayers = rankingPlayerCacheHandler.getRankingPlayerCache()
                .filterByGenderAndAgeClass(player.getPlayerInfo().getAgeClassGeneral(), player.getGender().getDisplayName());
        var sortedPlayers = filteredPlayers.stream()
                .sorted(Comparator.comparingInt(pointsExtractor).reversed())
                .toList();
        int rank = sortedPlayers.indexOf(player) + 1;
        Log.debugf("Calculated %s ranking for player %s is %d", rankingType, player.getFullName(), rank);
        return rank;
    }
}
