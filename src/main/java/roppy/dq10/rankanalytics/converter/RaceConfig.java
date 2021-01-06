package roppy.dq10.rankanalytics.converter;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum RaceConfig {
    SLIMERACE("slimerace", 1000, new NameIdentifierConfig[]{NameIdentifierConfig.SLIMERACE}),
    FISHING("fishing", 1000, new NameIdentifierConfig[]{
            NameIdentifierConfig.FISHING_TOP,NameIdentifierConfig.FISHING_BOTTOM}),
    BATTLE_PENCIL("pencil", 70, new NameIdentifierConfig[]{NameIdentifierConfig.BATTLE_PENCIL}),
    BATTLE_TRINITY("trinity", 1000, new NameIdentifierConfig[]{
            NameIdentifierConfig.BATTLE_TRINITY,NameIdentifierConfig.BATTLE_TRINITY,
            NameIdentifierConfig.BATTLE_TRINITY,NameIdentifierConfig.BATTLE_TRINITY,
            NameIdentifierConfig.BATTLE_TRINITY,NameIdentifierConfig.BATTLE_TRINITY}),
    DAIFUGO("daifugo", 70, new NameIdentifierConfig[]{
            NameIdentifierConfig.DAIFUGO}),
    DAIFUGOM("daifugom", 70, new NameIdentifierConfig[]{
            NameIdentifierConfig.DAIFUGOM}),
    CASINORAID("casinoraid", 1000, new NameIdentifierConfig[]{
            NameIdentifierConfig.CASINORAID_POKER,NameIdentifierConfig.CASINORAID_SLOT,
            NameIdentifierConfig.CASINORAID_ROULETTE});

    // S3のオブジェクトキーのプレフィックスと同一
    private String key;

    // FTP送信するSubraceListの最大数
    private int snapshotLengthLimit;

    // サブレースごとの設定を記述する
    private NameIdentifierConfig [] subraceNameIdentifierConfig;
}
