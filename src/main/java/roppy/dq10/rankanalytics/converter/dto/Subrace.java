package roppy.dq10.rankanalytics.converter.dto;

import lombok.Data;

import java.util.List;

@Data
public class Subrace {
    private List<RankSnapshot> snapshotList;

    private List<DisplayName> displayNameList;

    // ボーダーごとのAI解説文。生成に失敗した場合、または環境変数による無効化の場合は空のまま
    private List<BorderSummary> aiSummary;

    // AI解説文生成に失敗した場合の例外メッセージ。成功時・無効化時は空のまま
    private String aiSummaryError;
}
