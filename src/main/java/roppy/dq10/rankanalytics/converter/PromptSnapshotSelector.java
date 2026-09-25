package roppy.dq10.rankanalytics.converter;

import roppy.dq10.rankanalytics.converter.dto.BorderDefinition;
import roppy.dq10.rankanalytics.converter.dto.PromptPayload;
import roppy.dq10.rankanalytics.converter.dto.PromptSnapshot;
import roppy.dq10.rankanalytics.converter.dto.RaceRoundConfig;
import roppy.dq10.rankanalytics.converter.dto.RankItem;
import roppy.dq10.rankanalytics.converter.dto.RankSnapshot;
import roppy.dq10.rankanalytics.converter.dto.Subrace;

import java.util.ArrayList;
import java.util.List;

// Subrace + RaceRoundConfigから、AI解説文のプロンプトに含めるデータ(直近5スナップショット・各ボーダー前後20位)を選別する
public class PromptSnapshotSelector {

    private static final int RECENT_SNAPSHOT_COUNT = 5;

    private static final int WINDOW_MARGIN = 20;

    private static final PromptSnapshotSelector INSTANCE = new PromptSnapshotSelector();

    public static PromptSnapshotSelector getInstance() {
        return INSTANCE;
    }

    public PromptPayload select(Subrace subrace, RaceConfig raceConfig, RaceRoundConfig raceRoundConfig,
                                 int round, int subraceIndex) {
        List<RankSnapshot> snapshotList = subrace.getSnapshotList();
        int fromIndex = Math.max(snapshotList.size() - RECENT_SNAPSHOT_COUNT, 0);
        List<RankSnapshot> recentSnapshots = snapshotList.subList(fromIndex, snapshotList.size());

        List<PromptSnapshot> promptSnapshots = new ArrayList<>();
        for (RankSnapshot snapshot : recentSnapshots) {
            promptSnapshots.add(toPromptSnapshot(snapshot, raceRoundConfig));
        }

        int[] borders = new int[raceRoundConfig.getBorders().size()];
        for (int i = 0; i < borders.length; i++) {
            borders[i] = raceRoundConfig.getBorders().get(i).getRank();
        }

        PromptPayload payload = new PromptPayload();
        payload.setRaceKey(raceConfig.getKey());
        payload.setRound(round);
        payload.setSubraceIndex(subraceIndex);
        payload.setBorders(borders);
        payload.setSnapshots(promptSnapshots);
        return payload;
    }

    private PromptSnapshot toPromptSnapshot(RankSnapshot snapshot, RaceRoundConfig raceRoundConfig) {
        PromptSnapshot promptSnapshot = new PromptSnapshot();
        promptSnapshot.setTime(snapshot.getTimeString());

        List<RankItem> filtered = new ArrayList<>();
        for (RankItem item : snapshot.getRankList()) {
            if (isInAnyBorderWindow(item.getRank(), raceRoundConfig)) {
                filtered.add(item);
            }
        }
        promptSnapshot.setRankList(filtered);
        return promptSnapshot;
    }

    private boolean isInAnyBorderWindow(int rank, RaceRoundConfig raceRoundConfig) {
        for (BorderDefinition border : raceRoundConfig.getBorders()) {
            int lower = Math.max(1, border.getRank() - WINDOW_MARGIN);
            int upper = Math.min(raceRoundConfig.getRankBorder(), border.getRank() + WINDOW_MARGIN);
            if (rank >= lower && rank <= upper) {
                return true;
            }
        }
        return false;
    }
}
