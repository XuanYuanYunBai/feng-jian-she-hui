package social.feudal.npc;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.HexFormat;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerTextures;

public final class NpcSkins implements Listener {
    private static final UUID PACK=UUID.fromString("80674885-6ba7-4e84-9943-12ab33fcefef");
    private static final String URL="https://raw.githubusercontent.com/XuanYuanYunBai/feng-jian-she-hui/7360974411a3d874963bde12b9c96e879b2c5799/examples/imperial-atlas/feudal-people-v1.zip";
    static final String SHA1="6f18a83418ceb744e1bf7ddefc1961d33afcbeb4";
    private static final byte[] HASH=HexFormat.of().parseHex(SHA1);
    private final String url;
    public NpcSkins(Plugin plugin){
        this.url=plugin.getConfig().getString("npc.skin-pack-url",URL);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getOnlinePlayers().forEach(this::offer);
    }
    public static void apply(Mannequin body,String role){
        body.setProfile(ResolvableProfile.resolvableProfile().skinPatch(p->p.body(Key.key("feudal","entity/"+role))
                .model(PlayerTextures.SkinModel.CLASSIC)).build());
    }
    private void offer(Player p){if(!url.isBlank())p.addResourcePack(PACK,url,HASH,"加载《封建社会》的原创人物皮肤",false);}
    @EventHandler public void join(PlayerJoinEvent event){offer(event.getPlayer());}
}
