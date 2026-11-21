package social.feudal.survey;

import social.feudal.realm.RealmService;
import social.feudal.freight.FreightService;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import static social.feudal.survey.Survey.*;

public final class SurveyPlugin extends JavaPlugin {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetching = new AtomicBoolean();
    private volatile Progress progress;
    private volatile String failure;
    private volatile boolean closing;
    private SurveyStore store;
    private FreightService freight;
    private RealmService realm;
    private MapIdentity map;
    private Rules rules;
    private int limit;
    // Accessed on the server thread. Only count coordinates in a flushed batch.
    private Ring savedRing;
    private long savedFrom;
    private long savedUntil;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            rules = new Rules(getConfig().getInt("initial-radius"), getConfig().getInt("ring-step"),
                    new Ores(getConfig().getLong("minimum-blocks.iron"), getConfig().getLong("minimum-blocks.copper"),
                            getConfig().getLong("minimum-blocks.gold")));
            limit = getConfig().getInt("max-radius");
            int ticks = getConfig().getInt("ticks-between-chunks");
            if (limit < rules.initial() || limit > 1_875_000 || ticks < 1)
                throw new IllegalArgumentException("请核对半径预算与勘测间隔");
            World world = getServer().getWorlds().getFirst();
            if (world.getEnvironment() != World.Environment.NORMAL) throw new IllegalStateException("需要主世界");
            Location spawn = world.getSpawnLocation();
            String generator = world.getGenerator() == null ? "vanilla" : world.getGenerator().getClass().getName();
            map = new MapIdentity(world.getUID(), world.getSeed(), getServer().getMinecraftVersion() + "/" + generator,
                    spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
            Files.createDirectories(getDataFolder().toPath());
            Class.forName("org.sqlite.JDBC");
            store = new SurveyStore(getDataFolder().toPath().resolve("survey.sqlite"), rules);
            boolean legacy = store.schemaVersion() == 0;
            progress = store.load(map);
            if (legacy) getLogger().info("0.1.0 勘测记录已归档，按原中心复核。暂停状态请使用 /survey resume");
            if (progress != null && progress.ring().outer() > limit)
                throw new IllegalArgumentException("预算低于已登记半径，请恢复 max-radius");
            realm = new RealmService(this, world, getDataFolder().toPath().resolve("realm.sqlite"));
            freight = new FreightService(this, world, getDataFolder().toPath().resolve("freight.sqlite"));
            getServer().getScheduler().runTaskTimer(this, this::tick, 1L, ticks);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "勘测初始化失败", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 1) return false;
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (!List.of("start", "status", "pause", "resume").contains(action)) return false;
        worker.execute(() -> {
            String reply;
            try {
                if (progress == null && action.equals("start")) progress = store.start(map);
                else if (progress != null) {
                    Progress after = switch (action) {
                        case "pause" -> progress.phase() == Phase.RUNNING ? progress.phase(Phase.PAUSED) : progress;
                        case "resume" -> switch (progress.phase()) {
                            case PAUSED -> progress.phase(Phase.RUNNING);
                            case LIMIT -> progress.expand(rules, limit);
                            default -> progress;
                        };
                        default -> progress;
                    };
                    if (!after.equals(progress)) {
                        store.change(progress, after);
                        progress = after;
                    }
                }
                if (action.equals("resume")) failure = null;
                reply = progress == null ? "尚未开始，请使用 /survey start" : progress.report(rules);
                if (failure != null) reply += " | 勘测已中止：" + failure + "，排除后 /survey resume";
            } catch (Exception e) {
                reply = "命令失败：" + e.getMessage();
                getLogger().log(Level.WARNING, "处理勘测命令失败", e);
            }
            String response = reply;
            if (!closing) getServer().getScheduler().runTask(this, () -> sender.sendMessage(response));
        });
        return true;
    }

    private void tick() {
        Progress job = progress;
        if (job == null || job.phase() != Phase.RUNNING || failure != null || !fetching.compareAndSet(false, true)) return;
        try {
            World world = getServer().getWorld(job.map().id());
            if (world == null) throw new IllegalStateException("已登记的主世界未加载");
            if (!job.ring().equals(savedRing) || job.cursor() < savedFrom || job.cursor() >= savedUntil) {
                prepare(world, job);
                return;
            }
            Point p = job.next();
            getChunk(world, p, false).whenComplete((chunk, error) -> {
                if (closing) return;
                if (error != null) { stop(job, error); return; }
                if (chunk == null) { stop(job, new IllegalStateException("已存盘的区块缺失 " + p)); return; }
                Runnable capture = () -> capture(job, world, chunk);
                if (Bukkit.isPrimaryThread()) capture.run();
                else getServer().getScheduler().runTask(this, capture);
            });
        } catch (Exception e) {
            stop(job, e);
        }
    }

    private CompletableFuture<Chunk> getChunk(World world, Point p, boolean generate) {
        long x = 16L * p.x(), z = 16L * p.z();
        if (x < -29_999_984 || z < -29_999_984 || x + 15 >= 29_999_984 || z + 15 >= 29_999_984)
            throw new IllegalStateException("已到达世界硬边界 " + p);
        var border = world.getWorldBorder();
        if (!border.isInside(new Location(world, x + 0.5, 0, z + 0.5))
                || !border.isInside(new Location(world, x + 15.5, 0, z + 15.5)))
            throw new IllegalStateException("勘测区块触及现有世界边界 " + p);
        return world.getChunkAtAsync(p.x(), p.z(), generate);
    }

    private void prepare(World world, Progress job) {
        long end = Math.min(job.cursor() + 32, job.ring().size());
        var mainThread = getServer().getScheduler().getMainThreadExecutor(this);
        var chain = CompletableFuture.<Void>completedFuture(null);
        int cx = Math.floorDiv(job.map().spawnX(), 16), cz = Math.floorDiv(job.map().spawnZ(), 16);
        for (long i = job.cursor(); i < end; i++) {
            Point pos = job.ring().at(cx, cz, i);
            chain = chain.thenComposeAsync(ignored -> {
                if (closing || progress != job) throw new CancellationException();
                return getChunk(world, pos, true).thenAccept(chunk -> {
                    if (chunk == null) throw new IllegalStateException("区块生成失败 " + pos);
                });
            }, mainThread);
        }
        // Paper can complete generation before region-file I/O. Flush outside the load callback.
        chain.thenRunAsync(() -> {
            if (closing || progress != job) return;
            world.save(true);
            savedRing = job.ring();
            savedFrom = job.cursor();
            savedUntil = end;
        }, mainThread).whenComplete((ignored, error) -> {
            if (error != null) stop(job, error);
            else fetching.set(false);
        });
    }

    private void capture(Progress job, World world, Chunk chunk) {
        if (closing) return;
        if (progress != job) { fetching.set(false); return; }
        try {
            int bottom = world.getMinHeight(), ceiling = world.getMaxHeight();
            ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false, false);
            worker.execute(() -> {
                try {
                    if (progress != job) return;
                    progress = store.record(job, OreCounter.count(snapshot, bottom, ceiling), limit);
                    if (!job.ring().equals(progress.ring()) || progress.phase() != Phase.RUNNING)
                        getLogger().info(progress.report(rules));
                } catch (Exception e) {
                    stop(job, e);
                } finally {
                    fetching.set(false);
                }
            });
        } catch (Exception e) {
            stop(job, e);
        }
    }

    private void stop(Progress job, Throwable e) {
        if (progress == job) {
            failure = job.next() + " " + e;
            getLogger().log(Level.SEVERE, "已保留勘测进度：" + failure, e);
        }
        fetching.set(false);
    }

    @Override public void onDisable() {
        closing = true;
        try { if (realm != null) realm.close(); }
        catch (Exception e) { getLogger().log(Level.SEVERE, "关闭政治账本失败", e); }
        if (freight != null) freight.close();
        // The store closes on its owner thread, after already submitted measurements.
        worker.execute(() -> {
            try { if (store != null) store.close(); }
            catch (Exception e) { getLogger().log(Level.SEVERE, "关闭勘测数据库失败", e); }
        });
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) getLogger().warning("勘测事务仍在结束中");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
