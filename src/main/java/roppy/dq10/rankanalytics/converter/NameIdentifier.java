package roppy.dq10.rankanalytics.converter;

import roppy.dq10.rankanalytics.converter.dto.DisplayName;
import roppy.dq10.rankanalytics.converter.dto.Subrace;
import roppy.dq10.rankanalytics.converter.dto.RankItem;
import roppy.dq10.rankanalytics.converter.dto.RankSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NameIdentifier {
    private static final NameIdentifier INSTANCE = new NameIdentifier();
    private NameIdentifier(){}
    public static NameIdentifier getInstance(){return INSTANCE;}

    // 未識別状態を-1とする。
    private final static int INITIAL_ID = -1;

    public void identify(Subrace subrace, NameIdentifierConfig config) {
        //名前一覧を初期化
        subrace.setDisplayNameList(new ArrayList<>());

        //空の時は以後の処理はしない
        if(subrace.getSnapshotList().isEmpty()){
            return;
        }
        //idをすべて-1に初期化
        for(RankSnapshot snap : subrace.getSnapshotList()){
            for(RankItem item : snap.getRankList()){
                item.setId(INITIAL_ID);
            }
        }

        //1回目の全キャラに割り付けを行う
        RankSnapshot firstSnapshot = subrace.getSnapshotList().get(0);
        for(int i = 0;i<firstSnapshot.getRankList().size();i++){
            RankItem item = firstSnapshot.getRankList().get(i);
            int id = createNewDisplayName(subrace, item.getName(),item.isAnonymous());
            item.setId(id);
        }

        //前後を比較し、IDを割り当てる。新登場キャラには、新たなIDを割り当てる
        for(int snapIndex = 0; snapIndex < subrace.getSnapshotList().size()-1 ; snapIndex++) {
            RankSnapshot currentSnapshot = subrace.getSnapshotList().get(snapIndex);
            RankSnapshot nextSnapshot = subrace.getSnapshotList().get(snapIndex+1);

            List<Boolean> assignedList = new ArrayList<>();
            for(int currentIndex = 0 ; currentIndex < currentSnapshot.getRankList().size();currentIndex++){
                assignedList.add(false);
            }

            // 現在の最下位値を取得
            int currentLowestItemPoint =
                    currentSnapshot.getRankList().get(currentSnapshot.getRankList().size()-1).getPoint();

            // 次のランキングに残った人のidを保存
            Set<Integer> nextIdSet = new HashSet<>();

            // 更新低頻度レース(カジノレイド、釣り等)の場合は同ポイント同表示名を優先割付する
            if(config.isLowChangeFrequency()) {
                for(int currentIndex = 0 ; currentIndex < currentSnapshot.getRankList().size();currentIndex++){
                    RankItem currentItem = currentSnapshot.getRankList().get(currentIndex);
                    DisplayName currentDisplayName = subrace.getDisplayNameList().get(currentItem.getId());
                    for(int nextIndex = 0 ; nextIndex < nextSnapshot.getRankList().size();nextIndex++){
                        RankItem nextItem = nextSnapshot.getRankList().get(nextIndex);
                        if(currentItem.getPoint() == nextItem.getPoint()
                                && currentDisplayName.getName().equals(nextItem.getName())
                                && !nextItem.isAnonymous()
                                && nextItem.getId() == INITIAL_ID ){
                            nextItem.setId(currentItem.getId());
                            nextIdSet.add(currentItem.getId());
                            assignedList.set(currentIndex,true);
                            break;
                        }
                    }
                }
            }

            // 上位から順番に同名者優先割り付けを行う
            for(int currentIndex = 0 ; currentIndex < currentSnapshot.getRankList().size();currentIndex++){
                if(!assignedList.get(currentIndex)) {
                    RankItem currentItem = currentSnapshot.getRankList().get(currentIndex);
                    DisplayName currentDisplayName = subrace.getDisplayNameList().get(currentItem.getId());

                    for(int nextIndex = 0 ; nextIndex < nextSnapshot.getRankList().size();nextIndex++) {
                        RankItem nextItem = nextSnapshot.getRankList().get(nextIndex);
                        if(currentDisplayName.getName().equals(nextItem.getName())
                                && currentItem.getPoint() + config.getPointDiffUpperLimit() >= nextItem.getPoint()
                                && currentItem.getPoint() + config.getPointDiffLowerLimit() <= nextItem.getPoint()
                                && !nextItem.isAnonymous()
                                && nextItem.getId() == INITIAL_ID) {
                            nextItem.setId(currentItem.getId());
                            nextIdSet.add(currentItem.getId());
                            assignedList.set(currentIndex,true);
                            break;
                        }
                    }
                }
            }

            // 内緒の人の割り当てを行う
            // 点数が閾値以内にあるとして、
            // 前後ともに内緒
            // or (前後どちらかが内緒 かつ
            //    ((次のポイントが(現在の最下位値 + 差分上限値)よりも上 かつ 逆順フラグがfalse)
            //     or (次のポイントが(現在の最下位値 + 差分下限値)よりも下 かつ 逆順フラグがtrue))
            for(int currentIndex = 0 ; currentIndex < currentSnapshot.getRankList().size();currentIndex++){
                if(!assignedList.get(currentIndex)) {
                    RankItem currentItem = currentSnapshot.getRankList().get(currentIndex);
                    for(int nextIndex = 0 ; nextIndex < nextSnapshot.getRankList().size();nextIndex++) {
                        RankItem nextItem = nextSnapshot.getRankList().get(nextIndex);
                        if(currentItem.getPoint() + config.getPointDiffUpperLimit() >= nextItem.getPoint()
                                && currentItem.getPoint() + config.getPointDiffLowerLimit() <= nextItem.getPoint()
                                && ((currentItem.isAnonymous() && nextItem.isAnonymous())
                                 || ((currentItem.isAnonymous() || nextItem.isAnonymous())
                                  && ((currentLowestItemPoint + config.getPointDiffUpperLimit() <= nextItem.getPoint()
                                    && !config.isReverseOrder())
                                   || (currentLowestItemPoint + config.getPointDiffLowerLimit() >= nextItem.getPoint()
                                    && config.isReverseOrder()))))
                                && nextItem.getId() == INITIAL_ID) {
                            nextItem.setId(currentItem.getId());
                            nextIdSet.add(currentItem.getId());
                            // 内緒の人の名前が判明した場合は更新
                            if(!nextItem.isAnonymous()) {
                                DisplayName displayName = subrace.getDisplayNameList().get(currentItem.getId());
                                displayName.setAnonymous(false);
                                displayName.setName(nextItem.getName());
                            }
                            assignedList.set(currentIndex,true);
                            break;
                        }
                    }
                }
            }

            // 残った人は新しくランクインした人とする。
            // 過去ランクインした人は同じIDを割り当て、そうでない人は新しいIDを割り当てる
            for(int i = 0;i<nextSnapshot.getRankList().size();i++){
                RankItem item = nextSnapshot.getRankList().get(i);
                if(item.getId() == INITIAL_ID) {
                    for(int j = 0; j< subrace.getDisplayNameList().size(); j++) {
                        DisplayName displayName = subrace.getDisplayNameList().get(j);
                        if(!nextIdSet.contains(j) && !displayName.isAnonymous()
                                && displayName.getName().equals(item.getName())) {
                            item.setId(j);
                            break;
                        }
                    }
                }
                if(item.getId() == INITIAL_ID) {
                    int id = createNewDisplayName(subrace, item.getName(), item.isAnonymous());
                    item.setId(id);
                }
            }
        }


    }

    public int createNewDisplayName(Subrace subrace, String name, boolean anonymous) {
        int i = subrace.getDisplayNameList().size();

        DisplayName displayName = new DisplayName();
        displayName.setId(i);
        displayName.setName(name);
        displayName.setAnonymous(anonymous);

        subrace.getDisplayNameList().add(displayName);

        return i;
    }

}
