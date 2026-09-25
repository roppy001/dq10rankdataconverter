package roppy.dq10.rankanalytics.converter;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum RaceConfig {
    SLIMERACE("slimerace", "スライムレース", 1000, new NameIdentifierConfig[]{NameIdentifierConfig.SLIMERACE}),
    FISHING("fishing", "フィッシングコンテスト", 1000, new NameIdentifierConfig[]{
            NameIdentifierConfig.FISHING_TOP,NameIdentifierConfig.FISHING_BOTTOM}),
    BATTLE_PENCIL("pencil", "バトエン大会", 70, new NameIdentifierConfig[]{NameIdentifierConfig.BATTLE_PENCIL}),
    BATTLE_TRINITY("trinity", "バトルトリニティ", 1000, new NameIdentifierConfig[]{
            NameIdentifierConfig.BATTLE_TRINITY,NameIdentifierConfig.BATTLE_TRINITY,
            NameIdentifierConfig.BATTLE_TRINITY,NameIdentifierConfig.BATTLE_TRINITY,
            NameIdentifierConfig.BATTLE_TRINITY,NameIdentifierConfig.BATTLE_TRINITY}),
    DAIFUGO("daifugo", "大富豪段位戦", 70, new NameIdentifierConfig[]{
            NameIdentifierConfig.DAIFUGO}),
    DAIFUGOM("daifugom", "大富豪決定戦", 70, new NameIdentifierConfig[]{
            NameIdentifierConfig.DAIFUGOM}),
    CASINORAID("casinoraid", "カジノレイド", 1000, new NameIdentifierConfig[]{
            NameIdentifierConfig.CASINORAID_POKER,NameIdentifierConfig.CASINORAID_SLOT,
            NameIdentifierConfig.CASINORAID_ROULETTE,NameIdentifierConfig.CASINORAID_BINGO});

    // S3のオブジェクトキーのプレフィックスと同一。rankanalytics.jsのRACE_CONFIG_MAPのキー(例: slimerace7)の接頭辞とも一致する
    private String key;

    // AI解説文のシステムプロンプトに埋め込む表示名
    private String raceName;

    // FTP送信するSubraceListの最大数
    private int snapshotLengthLimit;

    // サブレースごとの設定を記述する
    private NameIdentifierConfig [] subraceNameIdentifierConfig;
}
