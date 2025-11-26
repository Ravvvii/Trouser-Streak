package pwn.noobs.trouserstreak.modules;

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoTool;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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
            .description("Safety: Never mine these blocks (even if targeted).")
            .visible(() -> listMode.get() == ListModes.Blacklist)
            .build());

    private final Setting<List<Block>> nonskippableBlox = sgGeneral.add(new BlockListSetting.Builder()
            .name("Blocks to Break")
            .description("Only instamine this block.")
            .visible(() -> listMode.get() == ListModes.Whitelist)
            .build());

    private final Setting<Integer> maxRange = sgGeneral.add(new IntSetting.Builder()
            .name("Max Range")
            .description("Radius search from the ORIGIN block.")
            .defaultValue(5)
            .min(1)
            .sliderMax(7)
            .build());

    private final Setting<Keybind> toggleKey = sgGeneral.add(new KeybindSetting.Builder()
            .name("Toggle Area Key")
            .description("Key to switch between Single Mode (Range 0) and Area Mode.")
            .defaultValue(Keybind.none())
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
    private final BlockPos.Mutable singleTargetPos = new BlockPos.Mutable(0, -128, 0);
    private final List<BlockPos> areaTargets = new ArrayList<>();
    private BlockPos originPos = null;
    private Block originBlockType = null;
    private Direction breakDirection;
    
    // Status Toggle
    private boolean areaModeActive = false;
    private boolean wasTogglePressed = false;
    
    // FIXED: Konstanta jarak aman (6 blok dari mata pemain)
    // 6 * 6 = 36. Kita kasih toleransi sedikit jadi 36-38.
    private static final double MAX_PLAYER_REACH_SQUARED = 36.0; 

    public SuperInstaMine() {
        super(Trouser.Main, "SuperInstaMine", "Fixed Range Bug & Infinite Swing.");
    }

    @Override
    public void onActivate() {
        ticks = 0;
        originPos = null;
        originBlockType = null;
        areaTargets.clear();
        singleTargetPos.set(0, -128, 0);
        areaModeActive = false;
    }

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        breakDirection = event.direction;
        BlockState clickedState = mc.world.getBlockState(event.blockPos);
        
        if (!areaModeActive) {
            singleTargetPos.set(event.blockPos);
            originPos = null;
        } else {
            originPos = event.blockPos;
            originBlockType = clickedState.getBlock();
            
            // Fix: Langsung hitung area saat klik pertama biar responsif
            calculateAreaTargets(); 
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // --- Logic Keybind Toggle ---
        if (toggleKey.get().isPressed()) {
            if (!wasTogglePressed) {
                areaModeActive = !areaModeActive;
                wasTogglePressed = true;
                
                String status = areaModeActive ? "AREA (Range " + maxRange.get() + ")" : "SINGLE (Range 0)";
                Formatting color = areaModeActive ? Formatting.GREEN : Formatting.YELLOW;
                ChatUtils.sendMsg(Text.literal("SuperInstaMine: ").append(Text.literal(status).formatted(color)));
                
                originPos = null;
                singleTargetPos.set(0, -128, 0);
            }
        } else {
            wasTogglePressed = false;
        }

        if (ticks >= tickDelay.get()) {
            ticks = 0;
            
            // --- MODE 1: SINGLE BLOCK ---
            if (!areaModeActive) {
                if (singleTargetPos.getY() == -128) return;
                // Fix: Cek jarak player juga untuk single block biar ga swing hantu
                if (mc.player.squaredDistanceTo(singleTargetPos.toCenterPos()) > MAX_PLAYER_REACH_SQUARED) return;
                performMining(singleTargetPos);
            } 
            
            // --- MODE 2: AREA MODE ---
            else {
                if (originPos == null || originBlockType == null) return;
                
                // Recalculate setiap tick (atau beberapa tick sekali) jika player bergerak
                // Ini penting agar daftar target selalu valid terhadap posisi player
                calculateAreaTargets(); 
                
                if (areaTargets.isEmpty()) {
                    originPos = null; // Stop jika tidak ada target valid
                    return;
                }

                for (BlockPos pos : areaTargets) {
                    performMining(pos);
                }
            }
            
        } else {
            ticks++;
        }
    }

    private void performMining(BlockPos pos) {
        if (mc.world.isOutOfHeightLimit(pos) || !BlockUtils.canBreak(pos)) return;

        BlockState state = mc.world.getBlockState(pos);
        if (!shouldMine(state)) return;

        if (areaModeActive && originBlockType != null) {
            if (state.getBlock() != originBlockType) return;
        }
        
        // FIX FINAL: Double Check jarak pemain sebelum kirim paket
        if (mc.player.squaredDistanceTo(pos.toCenterPos()) > MAX_PLAYER_REACH_SQUARED) return;

        if (useAutoTool.get() && Modules.get().isActive(AutoTool.class)) {
            equipBestTool(state);
        }

        Runnable miningAction = () -> {
            Direction dir = breakDirection == null ? Direction.UP : breakDirection;
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, dir));
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, dir));
            
            if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), miningAction);
        } else {
            miningAction.run();
        }
    }

    private void calculateAreaTargets() {
        areaTargets.clear();
        if (originPos == null) return;

        int r = maxRange.get();
        
        // FIX LOGIC:
        // Area pencarian tetap dari Origin Block (agar satu pohon kena semua)
        // TAPI... kita filter hasilnya berdasarkan jarak ke PLAYER.
        
        float pitch = mc.player.getPitch();
        boolean isVertical = (aorient.get() && (pitch > 30 || pitch < -30)) || (!aorient.get() && directionMode.get() == DirectionMode.Vertical);

        for (int i = 0; i <= r; i++) { // Start dari 0 (termasuk origin)
             for (int x = -i; x <= i; x++) {
                for (int y = -i; y <= i; y++) {
                    for (int z = -i; z <= i; z++) {
                        if (Math.abs(x) > i || Math.abs(y) > i || Math.abs(z) > i) continue;
                        
                        BlockPos target = originPos.add(x, y, z);
                        
                        // FIX UTAMA DISINI:
                        // Cek jarak target ke PEMAIN. Kalau > 6 blok, JANGAN dimasukkan ke target.
                        // Ini yang mencegah "pohon nyasar" yang jauh dari jangkauan tangan.
                        if (mc.player.squaredDistanceTo(target.toCenterPos()) > MAX_PLAYER_REACH_SQUARED) continue;

                        BlockState targetState = mc.world.getBlockState(target);
                        if (targetState.getBlock() == originBlockType) {
                            if (!areaTargets.contains(target)) areaTargets.add(target);
                        }
                    }
                }
             }
        }
    }
    
    private void equipBestTool(BlockState state) {
        int bestSlot = -1;
        double bestScore = -1;

        ItemStack currentStack = mc.player.getMainHandStack();
        double currentScore = AutoTool.getScore(currentStack, state, false, false, AutoTool.EnchantPreference.Fortune, itemStack -> true);
        
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            
            double score = AutoTool.getScore(stack, state, false, false, AutoTool.EnchantPreference.Fortune, itemStack -> true);

            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot != -1 && bestScore > currentScore) {
            InvUtils.swap(bestSlot, true);
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
        if (!render.get()) return;

        if (!areaModeActive) {
             if (singleTargetPos.getY() != -128 && BlockUtils.canBreak(singleTargetPos)) {
                 // Render single juga dicek jaraknya biar ga ngerender merah di kejauhan
                 if (mc.player.squaredDistanceTo(singleTargetPos.toCenterPos()) <= MAX_PLAYER_REACH_SQUARED) {
                     event.renderer.box(singleTargetPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                 }
             }
        } else {
            for (BlockPos pos : areaTargets) {
                if (BlockUtils.canBreak(pos)) {
                    event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                }
            }
        }
    }

    public enum DirectionMode {
        Horizontal, Vertical
    }

    public enum ListModes {
        Whitelist, Blacklist
    }
}
