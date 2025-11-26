package pwn.noobs.trouserstreak.modules;

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoTool;
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
            .name("Range")
            .description("0 = Single Block (Fastest). >0 = Area Mode.")
            .defaultValue(0)
            .min(0)
            .sliderMax(7)
            .build());

    private final Setting<Boolean> aorient = sgGeneral.add(new BoolSetting.Builder()
            .name("Auto Orient")
            .description("Automatically orients the breaking area based on your pitch.")
            .defaultValue(true)
            .visible(() -> range.get() > 0)
            .build());

    private final Setting<DirectionMode> directionMode = sgGeneral.add(new EnumSetting.Builder<DirectionMode>()
            .name("Direction Mode")
            .description("Forcing vertical or horizontal break.")
            .defaultValue(DirectionMode.Vertical)
            .visible(() -> !aorient.get() && range.get() > 0)
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
    
    // Optimization: Single Block Mode Variables
    private final BlockPos.Mutable singleTargetPos = new BlockPos.Mutable(0, -128, 0);
    
    // Optimization: Area Mode Variables
    private final List<BlockPos> areaTargets = new ArrayList<>();
    private BlockPos originPos = null;
    
    private Direction breakDirection;

    public SuperInstaMine() {
        super(Trouser.Main, "SuperInstaMine", "Instantly mines blocks. Range 0 = Optimized Single Target.");
    }

    @Override
    public void onActivate() {
        ticks = 0;
        originPos = null;
        areaTargets.clear();
        singleTargetPos.set(0, -128, 0);
    }

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        breakDirection = event.direction;
        
        // Update targets based on mode
        if (range.get() == 0) {
            singleTargetPos.set(event.blockPos);
            originPos = null; // Disable area mode tracking
        } else {
            originPos = event.blockPos; // Enable area mode tracking
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (ticks >= tickDelay.get()) {
            ticks = 0;
            
            // --- MODE 1: SINGLE BLOCK (FASTEST / LOW END PC) ---
            if (range.get() == 0) {
                if (singleTargetPos.getY() == -128) return; // No target set
                performMining(singleTargetPos);
            } 
            
            // --- MODE 2: AREA MODE ---
            else {
                if (originPos == null) return;
                
                // Safety check distance
                if (mc.player.squaredDistanceTo(originPos.toCenterPos()) > 49) { // 7 blocks radius
                    originPos = null;
                    return;
                }
                
                calculateAreaTargets(); // Recalculate area
                for (BlockPos pos : areaTargets) {
                    performMining(pos);
                }
            }
            
        } else {
            ticks++;
        }
    }

    // --- Mining Logic (The Core) ---
    private void performMining(BlockPos pos) {
        if (mc.world.isOutOfHeightLimit(pos) || !BlockUtils.canBreak(pos)) return;

        BlockState state = mc.world.getBlockState(pos);
        if (!shouldMine(state)) return;

        // AutoTool Logic
        if (useAutoTool.get() && Modules.get().isActive(AutoTool.class)) {
            equipBestTool(state);
        }

        Runnable miningAction = () -> {
            // Aggressive Rebreak Packet Logic (Start + Stop)
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

    // --- Area Calculation (Only used if Range > 0) ---
    private void calculateAreaTargets() {
        areaTargets.clear();
        areaTargets.add(originPos); // Add center

        int r = range.get();
        Direction playerFacing = mc.player.getHorizontalFacing();
        float pitch = mc.player.getPitch();
        boolean isVertical = (aorient.get() && (pitch > 30 || pitch < -30)) || (!aorient.get() && directionMode.get() == DirectionMode.Vertical);

        for (int i = 1; i <= r; i++) {
             // Simple expanding logic
             for (int x = -i; x <= i; x++) {
                for (int y = -i; y <= i; y++) {
                    for (int z = -i; z <= i; z++) {
                        if (Math.abs(x) > i || Math.abs(y) > i || Math.abs(z) > i) continue; // Hollow cube layers check if needed, strictly expanding
                        
                        // Basic filtering to create "InstaMine" shapes
                        BlockPos target = originPos.add(x, y, z);
                        if (!areaTargets.contains(target)) areaTargets.add(target);
                    }
                }
             }
        }
    }
    
    // --- Helper: AutoTool ---
    private void equipBestTool(BlockState state) {
        int bestSlot = -1;
        double bestScore = -1;

        // Check current item first to avoid unnecessary swapping
        ItemStack currentStack = mc.player.getMainHandStack();
        double currentScore = AutoTool.getScore(currentStack, state, false, false, AutoTool.EnchantPreference.Fortune, itemStack -> true);
        
        // If current tool is good enough (valid score), keep it? 
        // Or strictly search for better? Let's strictly search for better to ensure "Insta" break.

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            
            double score = AutoTool.getScore(stack, state, false, false, AutoTool.EnchantPreference.Fortune, itemStack -> true);

            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        // Only swap if we found a better tool AND it's better than current
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

        // Render Optimization for Low End PC
        if (range.get() == 0) {
            // Single Mode Render
             if (singleTargetPos.getY() != -128 && BlockUtils.canBreak(singleTargetPos)) {
                 event.renderer.box(singleTargetPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
             }
        } else {
            // Area Mode Render
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
