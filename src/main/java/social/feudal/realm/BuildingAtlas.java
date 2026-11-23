package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import org.bukkit.entity.Player;
import io.papermc.paper.registry.data.dialog.ActionButton;
import static social.feudal.ui.Menus.*;
import static social.feudal.realm.RealmStore.require;

final class BuildingAtlas {
    record Card(String name,String right,String description,String action,String cmd){}
    record Branch(String name,List<Card> cards){}
    private static Card plan(String name,String rights,String details){return new Card(name,rights,details,"","");}
    private static final List<Branch> TREE=List.of(
        new Branch("家宅与治理",List.of(
            new Card("首庄小院","私庄","9×9 围院，设厅位、赈济桶和一间带门民房。首批入住 100 人。","打开安家手册","start"),
            new Card("家族主宅","私庄","先扩院。大厅至少 35 格、净高三格；独立私室至少 9 格，设一张床和一个家用木桶。站在相应房间登记。","办理庄宅登记","estates"),
            new Card("公共议事厅","地方公议","在 35 格大厅配置讲台、钟、档案桶、两处旁听席。可独立建厅。建领还须 200 名实住居民、住房与一季公共服务。","查阅庄园与公示","estates"))),
        new Branch("住房与救济",List.of(
            new Card("居民民房","私庄","床旁留通道，设门、地板、围墙和屋顶。合格居民床容纳百人，登记的家族床供玩家自住。","现场复验住房","estates"),
            new Card("收容庄","私庄","实有空房、熟食，且两庄由登记陆路相连。庄主可签收容令。","查看庄簿","estates"),
            new Card("公共赈济所","领地","缺粮庄按实缺开单，供粮庄从厅仓封装面包，送达原批后签收开封。","选择办赈庄园","estates"))),
        new Branch("农业与食物",List.of(
            new Card("手磨与烤炉","私庄","磨石立在石基上，潜行持麦右键磨粉。三份粉在邻水木案揉面三次，烤足八秒。","备粮与安家","start"),
            new Card("水磨与烘焙房","私庄","从水磨入口沿可步行田路计程。一至三级分别为 32／48／64 格，最多 64／128／256 格田，1／2／4 个工位。","本庄水磨与田务","estates"),
            plan("果园、奶酪房与腌制房","私庄","果树占地过季，奶酪耗奶，耐储肉鱼耗盐和容器。"))),
        new Branch("矿冶与铁工",List.of(
            new Card("天然工作面","当地采掘","勘样只显示眼前矿面。用合适的镐可取得一份粗矿；矿位永久耗竭。一把无附魔的新铁镐可作业 25 次，普通铁砧用实铁修理，经验修补不恢复生产工具。","查验眼前矿面","sample"),
            new Card("大型矿场","领地","持印人在辖内天然矿区设坑口。木桶旁设两格高木支柱和工作台，留装卸路。在坑口存铁镐，再选矿面派班。","登记眼前坑口","pitform"),
            new Card("乡村铁匠铺","私庄","铁匠从料桶领铁、用炭锻造三次，桥路铁件回到仓内供运送修路。","查看庄内铁匠铺","estates"),
            plan("冶炼场、铸币所","私庄／领地","乡村铁工可接民用订单；规模化冶炼由领地批准，铸币另需特许，成品耗实料。"))),
        new Branch("林木与营造",List.of(plan("林场 → 木作场","私庄","林木轮伐复种，梁板供车船和工棚。"),plan("采石场 → 砖瓦窑 → 工程工棚","领地","登记工程占地，工棚签收实际送达的砖石和梁材。"))),
        new Branch("牲畜与纺织",List.of(plan("牲棚 → 马厩、驮畜栏","私庄","以现实存栏和越冬饲料安排繁育，驮畜须实际交付。"),plan("织坊、绳坊与鞍具坊","私庄","羊毛、亚麻和皮革分别制成衣料、帆索与鞍具。"))),
        new Branch("交通与贸易",List.of(
            new Card("道路、桥亭与渡口","依通行权","货运依已测道路与船道运行，桥旁亭可依税权以金银收费，断路时车辆停运。","查看当前路况","travel"),
            new Card("商栈与市集","依柜台权限","货主备货，买主备款。实物当面交割或凭货单沿路运送。","查看各地报价","prices"),
            new Card("公共养路","地方持印人与领内商户","持印人勘查登记路面的缺口。商户可出资购铁件，或由领地自备材料。完工后到现场验通。","打开养路簿","works"))),
        new Branch("教会与照料",List.of(plan("礼拜堂 → 修院","教产及领地","院产与地方公库分开登记，修院可承接供给合同。"),plan("施舍院、医舍与抄写室","教产","以实粮、药材、蜡与纸张安排接济和文书。"))),
        new Branch("守备与军需",List.of(plan("值守屋 → 军营、军械库","私庄值守／领地军营","守室可自建。征召须占用当地人口，领取装备和军粮要入账。"),plan("军用器械工坊","领地","木作和铁工按订单交料，成品由队伍沿可行道路运输。"))));
    static void open(RealmService realm,Player p,String[] a)throws SQLException{
        RealmService.usage(a.length<=3,"buildings [分支] [建筑]");
        var body=new ArrayList<io.papermc.paper.registry.data.dialog.body.DialogBody>();
        var buttons=new ArrayList<ActionButton>();String title="建筑图谱";
        if(a.length==1){
            body.add(text("从私庄的房屋与生计开始。点分支查看建筑、权属、材料和现场操作。"));
            for(int i=0;i<TREE.size();i++)buttons.add(command(TREE.get(i).name(),"realm buildings "+(i+1)));
        }else{
            int index=Integer.parseInt(a[1])-1;require(index>=0 && index<TREE.size(),"未知建筑分支");
            var branch=TREE.get(index);title=branch.name();
            if(a.length==2){
                for(int i=0;i<branch.cards.size();i++){
                    var c=branch.cards.get(i);
                    buttons.add(command(c.name(),"realm buildings "+a[1]+" "+(i+1)));
                }
            }else{
                int i=Integer.parseInt(a[2])-1;require(i>=0 && i<branch.cards.size(),"未知建筑");
                var c=branch.cards.get(i);title=c.name();
                body.add(text(branch.name()+"　›　"+c.name()));body.add(text("权属　"+c.right()));
                body.add(text(c.description()));
                if(c.cmd().isEmpty())body.add(text("列入首发路线，尚未开放作业。"));
                else buttons.add(command(c.action(),"realm "+c.cmd()));
                if(c.cmd().equals("estates")){
                    var owned=realm.solePersonalManor(p);
                    if(owned!=null)buttons.add(command("打开我的庄档","realm manor "+owned));
                }
                buttons.add(command("返回"+branch.name(),"realm buildings "+a[1]));
            }
            buttons.add(command("图谱总览","realm buildings"));
        }
        show(p,title,body,List.of(),buttons,2);
    }
}
