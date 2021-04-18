package roppy.dq10.rankanalytics.converter;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum NameIdentifierConfig {
    SLIMERACE(false,false,32000000,-2000000),
    BATTLE_TRINITY(false,false,60,0),
    BATTLE_PENCIL(false,false,2000,0),
    FISHING_TOP(true,false,50000,0),
    FISHING_BOTTOM(true,true,0,-10000),
    DAIFUGO(false,false,400,-300),
    DAIFUGOM(false,false,400,-300),
    CASINORAID_POKER(true,false,1000000,0),
    CASINORAID_SLOT(true,false,500000,0),
    CASINORAID_ROULETTE(true,false,500000,0),
    CASINORAID_BINGO(true,false,500000,0);


    // 名前とポイントが同じキャラを優先的に紐づけるか否か
    private boolean lowChangeFrequency;

    // 釣りコンテストの最小値ランキングなど、ポイントの低さを争うレースの時はtrueとする。
    private boolean reverseOrder;

    // ポイント差分の上限値
    private int pointDiffUpperLimit;

    // ポイント差分の下限値
    private int pointDiffLowerLimit;

}
