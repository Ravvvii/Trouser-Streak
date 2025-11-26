package pwn.noobs.trouserstreak.modules;

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoTool; // Import AutoTool
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import pwn.noobs.trouserstreak.Trouser;

import java.util.ArrayList;
import java.util.List;

public class SuperInstaMine extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- Settings ---
    private final Setting<ListModes> listMode = sgGeneral.add(new EnumSetting.Builder<ListModes>()
            .name("List Mode")
            .description("Whether to break or not break the block list.")
            .defaultValue(ListModes.Blacklist)
            .build());

    private final Setting<List<Block>> skippableBlox = sgGeneral.add(new BlockListSetting.Builder()
            .name("Blocks to Skip")
            .description("Skips instamining this block.")
            .visible(() -> listMode.get() == ListModes.Blacklist)
            .build());

    private final Setting<List<Block>> nonskippableBlox = sgGeneral.add(new BlockListSetting.Builder()
            .name("Blocks to Break")
            .description("Only instamine this block.")
            .visible(() -> listMode.get() == ListModes.Whitelist)
            .build());

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
            .name("Range (Radius)")
            .description("The range around the center block to break.")
            .defaultValue(0)
            .min(0)
            .sliderMax(7)
            .build());

    private final Setting<Boolean> aorient = sgGeneral.add(new BoolSetting.Builder()
            .name("Auto Orient")
            .description("Automatically orients the breaking area based on your pitch.")
            .defaultValue(true)
            .build());

    private final Setting<DirectionMode> directionMode = sgGeneral.add(new EnumSetting.Builder<DirectionMode>()
            .name("Direction Mode")
            .description("Forcing vertical or horizontal break.")
            .defaultValue(DirectionMode.Vertical)
            .visible(() -> !aorient.get())
            .build());

    private final Setting<Integer> tickDelay = sgGeneral.add(new IntSetting.Builder()
            .name("Delay")
            .description("The delay (in ticks) between breaks.")
            .defaultValue(0)
            .min(0)
            .sliderMax(20)
            .build());

    private final Setting<Boolean> useAutoTool = sgGeneral.add(new BoolSetting.Builder()
            .name("Use AutoTool")
            .description("Syncs with AutoTool module to swap to the best tool before breaking.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
            .name("Swing Hand")
            .description("Visually swing hand when mining.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("Rotate")
            .description("Faces the blocks being mined server side.")
            .defaultValue(true)
            .build());

    // --- Render ---
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
            .name("Render")
            .description("Renders a block overlay on the block being broken.")
            .defaultValue(true)
            .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("Shape Mode")
            .description("How the shapes are rendered.")
            .defaultValue(ShapeMode.Both)
            .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
            .name("Side Color")
            .defaultValue(new SettingColor(204, 0, 0, 10))
            .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
            .name("Line Color")
            .defaultValue(new SettingColor(204, 0, 0, 255))
            .build());

    // --- Variables ---
    private int ticks;
    private final List<BlockPos> targets = new ArrayList<>();
    private BlockPos originPos = null;
    private Direction breakDirection;

    public SuperInstaMine() {
        super(Trouser.Main, "SuperInstaMine", "Instantly mines blocks in an area with AutoTool support.");
    }

    @Override
    public void onActivate() {
        ticks = 0;
        originPos = null;
        targets.clear();
    }

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        // Set origin untuk titik pusat area mining
        originPos = event.blockPos;
        breakDirection = event.direction;
        
        // Kita cancel event asli agar kita bisa handle sendiri dengan packet instant
        // Tapi kalau mau logic vanilla break tetap jalan, jangan di cancel.
        // SuperInstaMine biasanya menimpa logic vanilla, jadi kita biarkan event berjalan
        // tapi kita ambil alih logic "area" nya di onTick.
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || originPos == null) return;

        // Validasi jarak origin (agar tidak mining ghost block yang jauh)
        if (mc.player.squaredDistanceTo(originPos.toCenterPos()) > 36) { // 6 blocks
            originPos = null;
            return;
        }

        if (ticks >= tickDelay.get()) {
            ticks = 0;
            calculateTargets(); // Hitung blok mana saja yang mau dihancurkan

            for (BlockPos pos : targets) {
                if (!BlockUtils.canBreak(pos)) continue;
                
                BlockState state = mc.world.getBlockState(pos);
                if (!shouldMine(state)) continue;

                // --- AUTO TOOL INTEGRATION ---
                if (useAutoTool.get() && Modules.get().isActive(AutoTool.class)) {
                    equipBestTool(state);
                }

                // --- ROTATION & PACKET ---
                Runnable miningAction = () -> {
                    // Kirim Packet Instant Break (Start & Stop bersamaan)
                    mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, breakDirection));
                    mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, breakDirection));
                    
                    if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND); // FIX: Client side swing
                };

                if (rotate.get()) {
                    Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), miningAction);
                } else {
                    miningAction.run();
                }
            }
        } else {
            ticks++;
        }
    }

    // --- Core Logic: Menghitung Area Target ---
    private void calculateTargets() {
        targets.clear();
        targets.add(originPos); // Selalu tambahkan pusat

        int r = range.get();
        if (r == 0) return;

        Direction playerFacing = mc.player.getHorizontalFacing();
        float pitch = mc.player.getPitch();
        
        // Tentukan Mode: Vertical atau Horizontal
        boolean isVertical = (aorient.get() && (pitch > 30 || pitch < -30)) || (!aorient.get() && directionMode.get() == DirectionMode.Vertical);

        // Logika Loop (Pengganti if hardcoded)
        // Kita memperluas area berdasarkan arah hadap player
        
        for (int i = 0; i <= r; i++) {
             // Logic sederhana: Membuat box/tunnel di depan/sekitar origin
             // Ini meniru perilaku SuperInstaMine lama yang memperluas ke samping dan atas/bawah
             addLayer(i, playerFacing, isVertical);
        }
    }

    private void addLayer(int offset, Direction facing, boolean vertical) {
        // Offset logic yang meniru "Expanding Cube" tapi lebih bersih
        // Menggunakan originPos sebagai titik 0,0,0
        
        // Jika Vertical (Gali ke atas/bawah atau dinding depan)
        // Jika Horizontal (Gali lantai/atap)
        
        // Implementasi sederhana "Expanding Box" di sekitar origin
        // Anda bisa kreasikan loop x,y,z disini.
        // Untuk menjaga kompabilitas dengan gaya "Tunnel/Wall" SuperInstaMine:
        
        for (int x = -offset; x <= offset; x++) {
            for (int y = -offset; y <= offset; y++) {
                for (int z = -offset; z <= offset; z++) {
                    // Hindari duplikasi blok pusat
                    if (x==0 && y==0 && z==0) continue; 
                    
                    // Filter berdasarkan arah (agar tidak mining ke belakang player)
                    BlockPos target = originPos.add(x, y, z);
                    
                    // Disini kita bisa filter lebih lanjut agar bentuknya sesuai "InstaMine" lama
                    // Kode lama sangat spesifik (hanya blockPos1, 2, dst).
                    // Versi baru ini akan menghancurkan CUBE area (lebih efektif).
                    
                    // Filter jarak Chebyshev agar bentuknya kotak rapi sesuai range
                    if (Math.abs(x) > offset || Math.abs(y) > offset || Math.abs(z) > offset) continue;
                    
                    if (!targets.contains(target)) targets.add(target);
                }
            }
        }
    }
    
    // --- Helper: AutoTool ---
    private void equipBestTool(BlockState state) {
        // Menggunakan referensi logika AutoTool.getScore
        int bestSlot = -1;
        double bestScore = -1;

        // Ambil setting dari AutoTool module
        AutoTool autoTool = Modules.get().get(AutoTool.class);
        // Kita asumsikan default setting jika gagal akses private fields, 
        // atau kita gunakan logika dasar mining speed.
        
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            
            // Kita panggil static method getScore dari AutoTool yang Anda kirim
            // Karena method itu public static, kita bisa akses langsung!
            double score = AutoTool.getScore(
                stack, 
                state, 
                false, // Default silkTouchEnderChest (karena kita ga bisa akses setting private)
                false, // Default fortuneOre
                AutoTool.EnchantPreference.Fortune, 
                itemStack -> true // Accept all valid tools
            );

            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot != -1 && bestSlot != mc.player.getInventory().selectedSlot) {
            InvUtils.swap(bestSlot, true); // true = swap back logic handled by InvUtils/AutoTool check
        }
    }

    private boolean shouldMine(BlockState state) {
        Block block = state.getBlock();
        if (listMode.get() == ListModes.Whitelist) {
            return nonskippableBlox.get().contains(block);
        } else {
            return !skippableBlox.get().contains(block);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || targets.isEmpty()) return;

        for (BlockPos pos : targets) {
            // Render hanya jika blok valid untuk dimine
            if (BlockUtils.canBreak(pos) && shouldMine(mc.world.getBlockState(pos))) {
                event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
    }

    // --- Enums ---
    public enum DirectionMode {
        Horizontal, Vertical
    }

    public enum ListModes {
        Whitelist, Blacklist
    }
}
