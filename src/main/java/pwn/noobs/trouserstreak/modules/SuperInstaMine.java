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
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import pwn.noobs.trouserstreak.Trouser;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
            .description("Safety: Never mine these blocks.")
            .visible(() -> listMode.get() == ListModes.Blacklist)
            .build());

    private final Setting<List<Block>> nonskippableBlox = sgGeneral.add(new BlockListSetting.Builder()
            .name("Blocks to Break")
            .description("Only instamine this block.")
            .visible(() -> listMode.get() == ListModes.Whitelist)
            .build());

    private final Setting<Integer> maxRange = sgGeneral.add(new IntSetting.Builder()
            .name("Range")
            .description("0 = Manual Queue (Infinite). >0 = Auto Area.")
            .defaultValue(0)
            .min(0)
            .sliderMax(7)
            .build());

    private final Setting<Keybind> toggleKey = sgGeneral.add(new KeybindSetting.Builder()
            .name("Area Toggle Key")
            .description("Turn ON to enable Area Calculation (If Range > 0).")
            .defaultValue(Keybind.none())
            .visible(() -> maxRange.get() > 0)
            .build());

    private final Setting<Boolean> aorient = sgGeneral.add(new BoolSetting.Builder()
            .name("Auto Orient")
            .description("Automatically orients the breaking area based on your pitch.")
            .defaultValue(true)
            .visible(() -> maxRange.get() > 0)
            .build());

    private final Setting<DirectionMode> directionMode = sgGeneral.add(new EnumSetting.Builder<DirectionMode>()
            .name("Direction Mode")
            .description("Forcing vertical or horizontal break.")
            .defaultValue(DirectionMode.Vertical)
            .visible(() -> !aorient.get() && maxRange.get() > 0)
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

    private final Setting<SwingMode> swingMode = sgGeneral.add(new EnumSetting.Builder<SwingMode>()
            .name("Swing Mode")
            .description("Client: Visual+Packet. Server: Packet Only (Invisible).")
            .defaultValue(SwingMode.Server)
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
    private final List<BlockPos> miningQueue = new CopyOnWriteArrayList<>();
    private Direction breakDirection;
    
    private boolean areaModeEnabled = true; 
    private boolean wasTogglePressed = false;
    
    private static final double MAX_PLAYER_REACH_SQUARED = 36.0; 

    public SuperInstaMine() {
        super(Trouser.Main, "SuperInstaMine", "Fixed Multi-Block Queue Logic.");
    }

    @Override
    public void onActivate() {
        ticks = 0;
        miningQueue.clear();
        areaModeEnabled = true; 
    }

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        breakDirection = event.direction;
        
        // PERBAIKAN UTAMA: Gunakan .toImmutable()
        // Ini memastikan koordinat yang disimpan "dikunci" dan tidak berubah saat kursor geser.
        BlockPos immutablePos = event.blockPos.toImmutable();
        
        // 1. Tambahkan ke antrian (Manual)
        addBlockToQueue(immutablePos);

        // 2. Logic Area (Hanya jika Range > 0)
        if (maxRange.get() > 0 && areaModeEnabled) {
            calculateAndAddNeighbors(immutablePos);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // --- Logic Keybind Toggle ---
        if (maxRange.get() > 0 && toggleKey.get().isPressed()) {
            if (!wasTogglePressed) {
                areaModeEnabled = !areaModeEnabled;
                wasTogglePressed = true;
                
                String status = areaModeEnabled ? "AREA ON" : "AREA OFF (Manual Only)";
                Formatting color = areaModeEnabled ? Formatting.GREEN : Formatting.RED;
                ChatUtils.sendMsg(Text.literal("SuperInstaMine: ").append(Text.literal(status).formatted(color)));
            }
        } else {
            wasTogglePressed = false;
        }

        if (ticks >= tickDelay.get()) {
            ticks = 0;
            
            // Bersihkan antrian hanya jika blok benar-benar hancur (AIR)
            miningQueue.removeIf(pos -> {
                // Hapus jika blok sudah jadi udara
                if (!BlockUtils.canBreak(pos) || mc.world.getBlockState(pos).getBlock() == Blocks.AIR) return true;
                // Hapus jika kejauhan
                if (mc.player.squaredDistanceTo(pos.toCenterPos()) > MAX_PLAYER_REACH_SQUARED) return true;
                // Hapus jika blok masuk blacklist setting
                if (!shouldMine(mc.world.getBlockState(pos))) return true;
                return false;
            });

            // Eksekusi Mining untuk SEMUA blok di antrian
            for (BlockPos pos : miningQueue) {
                performMining(pos);
            }
            
        } else {
            ticks++;
        }
    }

    private void addBlockToQueue(BlockPos pos) {
        // Pastikan pos belum ada di antrian (cegah duplikat)
        if (!miningQueue.contains(pos) && mc.player.squaredDistanceTo(pos.toCenterPos()) <= MAX_PLAYER_REACH_SQUARED) {
            miningQueue.add(pos);
        }
    }

    private void calculateAndAddNeighbors(BlockPos originPos) {
        int r = maxRange.get();
        if (r == 0) return;

        Block originBlockType = mc.world.getBlockState(originPos).getBlock();
        
        float pitch = mc.player.getPitch();
        boolean isVertical = (aorient.get() && (pitch > 30 || pitch < -30)) || (!aorient.get() && directionMode.get() == DirectionMode.Vertical);

        for (int i = 0; i <= r; i++) { 
             for (int x = -i; x <= i; x++) {
                for (int y = -i; y <= i; y++) {
                    for (int z = -i; z <= i; z++) {
                        if (Math.abs(x) > i || Math.abs(y) > i || Math.abs(z) > i) continue;
                        
                        // Gunakan toImmutable juga disini untuk keamanan ekstra
                        BlockPos target = originPos.add(x, y, z).toImmutable();
                        
                        if (mc.player.squaredDistanceTo(target.toCenterPos()) > MAX_PLAYER_REACH_SQUARED) continue;

                        if (mc.world.getBlockState(target).getBlock() == originBlockType) {
                            addBlockToQueue(target);
                        }
                    }
                }
             }
        }
    }

    private void performMining(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        
        if (useAutoTool.get() && Modules.get().isActive(AutoTool.class)) {
            equipBestTool(state);
        }

        Runnable miningAction = () -> {
            Direction dir = breakDirection == null ? Direction.UP : breakDirection;
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, dir));
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, dir));
            
            switch (swingMode.get()) {
                case Client: mc.player.swingHand(Hand.MAIN_HAND); break;
                case Server: mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND)); break;
            }
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), miningAction);
        } else {
            miningAction.run();
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

        for (BlockPos pos : miningQueue) {
            if (BlockUtils.canBreak(pos)) {
                event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
    }

    public enum DirectionMode {
        Horizontal, Vertical
    }

    public enum ListModes {
        Whitelist, Blacklist
    }

    public enum SwingMode {
        Client, Server, None
    }
}
