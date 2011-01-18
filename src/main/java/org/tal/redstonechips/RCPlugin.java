/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.tal.redstonechips;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockDamageLevel;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Event.Type;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockListener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockRightClickEvent;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.tal.redstonechips.circuits.Circuit;
import org.tal.redstonechips.circuits.receiver;
import org.tal.redstonechips.circuits.transmitter;


/**
 *
 * @author Tal Eisenberg
 */
public class RCPlugin extends JavaPlugin {
    public final static String circuitPackage = "org.tal.redstonechips.circuits";

    private final PluginDescriptionFile pdf;

    public final static String circuitsFileName = "redstonechips.dat";

    private Material blockType = Material.SANDSTONE;
    private Material inputBlockType = Material.IRON_BLOCK;
    private Material outputBlockType = Material.GOLD_BLOCK;

    static final Logger log = Logger.getLogger("Minecraft");
    private BlockListener rcBlockListener;

    public static List<transmitter> transmitters = new ArrayList<transmitter>();
    public static List<receiver> receivers = new ArrayList<receiver>();

    private List<Circuit> circuits;


    public RCPlugin(PluginLoader pluginLoader, Server instance, PluginDescriptionFile desc, File folder, File plugin, ClassLoader cLoader) {
        super(pluginLoader, instance, desc, folder, plugin, cLoader);
        this.pdf = desc;

        rcBlockListener = new BlockListener() {

            @Override
            public void onBlockRedstoneChange(BlockFromToEvent event) {
                redstoneChange((BlockRedstoneEvent)event);
            }

            @Override
            public void onBlockRightClick(BlockRightClickEvent event) {
                checkForCircuit(event);
            }

            @Override
            public void onBlockDamage(BlockDamageEvent event) {
                if (event.getDamageLevel()==BlockDamageLevel.BROKEN)
                    checkCircuitDestroyed(event.getBlock(), event.getPlayer());
            }
        };
    }

    @Override
    public void onDisable() {
        log.info(pdf.getName() + " " + pdf.getVersion() + " disabled.");
    }

    @Override
    public void onEnable() {
        PluginManager pm = getServer().getPluginManager();

        loadProperties();
        loadCircuits(getServer().getWorlds()[0]);


        pm.registerEvent(Type.REDSTONE_CHANGE, rcBlockListener, Priority.Normal, this);
        pm.registerEvent(Type.BLOCK_RIGHTCLICKED, rcBlockListener, Priority.Monitor, this);
        pm.registerEvent(Type.BLOCK_DAMAGED, rcBlockListener, Priority.Monitor, this);

        log.info(pdf.getName() + " " + pdf.getVersion() + " enabled.");
    }

    @Override
    public boolean onCommand(Player player, Command cmd, String commandLabel, String[] args) {
        if (cmd.getName().equals("/redchips-list")) {
            listCircuits(player);
            return true;
        } return false;
    }

    private void loadProperties() {
        Properties props = new Properties();
        File propFile = new File(pdf.getName().toLowerCase() + ".properties");
        if (!propFile.exists()) { // create empty file if doesn't already exist
            try {
                props.store(new FileOutputStream(propFile), "");
            } catch (IOException ex) {
                log.log(Level.SEVERE, null, ex);
            }
        }

        try {

            log.info(pdf.getName() + ": properties loaded.");

            if (props.containsKey("inputBlockType")) {
                inputBlockType = Material.getMaterial(props.getProperty("inputBlockType").toUpperCase());
            } else props.setProperty("inputBlockType", inputBlockType.name());

            if (props.containsKey("outputBlockType")) {
                outputBlockType = Material.getMaterial(props.getProperty("outputBlockType").toUpperCase());
            } else props.setProperty("outputBlockType", outputBlockType.name());

            if (props.containsKey("blockType")) {
                blockType = Material.getMaterial(props.getProperty("blockType"));
            } else props.setProperty("blockType", blockType.name());

            props.store(new FileOutputStream(propFile), "");
        } catch (IOException ex) {
            log.log(Level.WARNING, null, ex);
        }
    }

    private void loadCircuits(World w) {
        Properties props = new Properties();
        File propFile = new File(circuitsFileName);
        if (!propFile.exists()) { // create empty file if doesn't already exist
            try {
                props.store(new FileOutputStream(propFile), "");
            } catch (IOException ex) {
                log.log(Level.SEVERE, null, ex);
            }
        }

        circuits = new ArrayList<Circuit>();

        try {
            props.load(new FileInputStream(propFile));
            for (String id : props.stringPropertyNames()) {
                String circuitString = props.getProperty(id);
                Circuit c = Circuit.fromFileString(circuitString, w);
                if (c==null) log.warning(pdf.getName() + ": Error while loading circuit: " + circuitString);
                else circuits.add(c);
            }
            log.info(pdf.getName() + ": Loaded " + circuits.size() + " circuits");
        } catch (Exception ex) {
            log.log(Level.SEVERE, null, ex);
        }

    }

    private void saveCircuits() {
        Properties props = new Properties();
        File propFile = new File(circuitsFileName);

        for (Circuit c : circuits) {
            props.setProperty(""+circuits.indexOf(c), c.toFileString());
        }

        try {
            props.store(new FileOutputStream(propFile), "");
        } catch (IOException ex) {
            log.log(Level.SEVERE, null, ex);
        }
    }

    public void listCircuits(Player p) {
        p.sendMessage("Currently activated redstone circuits: ");
        for (Circuit c : circuits)
            p.sendMessage(circuits.indexOf(c) + ": " + c.getClass().getName() + " @ " + c.activationBlock.getX() + ", " + c.activationBlock.getY() + ", " + c.activationBlock.getZ());

    }

    private void redstoneChange(BlockRedstoneEvent e) {

        boolean newVal = (e.getNewCurrent()>0);
        boolean oldVal = (e.getOldCurrent()>0);

        Block block = e.getBlock();
        getServer().broadcastMessage("Block " + block.getX() + "," + block.getY() + "," + block.getZ() + " turned from " + e.getOldCurrent() + " to " + e.getNewCurrent());

        if (newVal!=oldVal) {
            // check if the block is an input of one of the circuits
            for (Circuit c : circuits) c.redstoneChange(e.getBlock(), newVal);
        }
    }

    private void checkForCircuit(BlockRightClickEvent event) {
        Block b = event.getBlock();
        Player player = event.getPlayer();

        if (b.getType()==Material.WALL_SIGN) {
            int x = b.getX();
            int y = b.getY();
            int z = b.getZ();

            // first check if its already registered
            for (Circuit c : circuits) {
                if (c.activationBlock.equals(b)) {
                    player.sendMessage("Circuit is already activated.");
                    return;
                }
            }

            // try to detect a circuit in any possible orientation (N,W,S or E)
            if (b.getFace(BlockFace.EAST).getType()==blockType) {
                if (detectCircuit(b, BlockFace.EAST, player)) return;
            }

            if (b.getFace(BlockFace.WEST).getType()==blockType) {
                if (detectCircuit(b, BlockFace.WEST, player)) return;
            }

            if (b.getFace(BlockFace.NORTH).getType()==blockType) {
                if (detectCircuit(b, BlockFace.NORTH, player)) return;
            }

            if (b.getFace(BlockFace.SOUTH).getType()==blockType) {
                if (detectCircuit(b, BlockFace.SOUTH, player)) return;
            }
        }
    }

    private boolean detectCircuit(Block blockClicked, BlockFace direction, Player player) {
        player.sendMessage("detectCircuit: " + direction.name() + " block: " + blockClicked.getX() + "," + blockClicked.getY() + "," + blockClicked.getZ() + " " + blockClicked.getType().name());
        List<Block> inputs = new ArrayList<Block>();
        List<Block> outputs = new ArrayList<Block>();
        List<Block> structure = new ArrayList<Block>();
        Block curPlus1, curMinus1, curBlock;
        boolean xAxis = (direction==BlockFace.SOUTH || direction==BlockFace.NORTH);
        structure.add(blockClicked);
        curBlock = blockClicked.getFace(direction);
        curPlus1 = curBlock.getRelative((xAxis?0:1), 0, (xAxis?1:0));
        curMinus1 = curBlock.getRelative((xAxis?0:-1), 0, (xAxis?-1:0));

        do {
            if (curPlus1.getType()==inputBlockType || curPlus1.getType()==outputBlockType) {
                structure.add(curPlus1);
                Block jack = curPlus1.getRelative((xAxis?0:1), 0, (xAxis?1:0));
                if (curPlus1.getType()==inputBlockType) {
                    inputs.add(jack);
                    player.sendMessage("input at " + jack.toString());
                } else {
                    outputs.add(jack);
                    structure.add(jack);
                    player.sendMessage("output at " + jack.toString());
                }
            }

            if (curMinus1.getType()==inputBlockType || curMinus1.getType()==outputBlockType) {
                structure.add(curMinus1);
                Block jack = curMinus1.getRelative((xAxis?0:-1), 0, (xAxis?-1:0));
                if (curMinus1.getType()==inputBlockType) {
                    inputs.add(jack);
                    player.sendMessage("input at " + jack.toString());
                } else {
                    outputs.add(jack);
                    structure.add(jack);
                    player.sendMessage("output at " + jack.toString());
                }
            }

            structure.add(curBlock); // a line block.

            // iterate forward
            curBlock = curBlock.getFace(direction);
            curPlus1 = curBlock.getRelative((xAxis?0:1), 0, (xAxis?1:0));
            curMinus1 = curBlock.getRelative((xAxis?0:-1), 0, (xAxis?-1:0));
        } while(curBlock.getType()==blockType);

        Block outputBlock = curBlock;
        Block lastLineBlock = structure.get(structure.size()-1);

        for (Block o : outputs) {
            if (o.getType()!=Material.REDSTONE_WIRE) {
                return false;
            }
        }

        if (outputs.size()>0 || inputs.size()>0) {
            // we have a circuit
            Sign sign = (Sign)blockClicked.getState();
            String sargs = sign.getLine(1) + " " + sign.getLine(2) + " " + sign.getLine(3);
            StringTokenizer t = new StringTokenizer(sargs);
            String[] args = new String[t.countTokens()];
            int i = 0;
            while (t.hasMoreElements()) {
                args[i] = t.nextToken();
                i++;
            }

            try {
                Circuit c = Circuit.getInstance(RCPlugin.circuitPackage + "." + sign.getLine(0).trim());
                c.activationBlock = blockClicked;
                c.outputBlock = outputBlock;
                c.inputs = inputs.toArray(new Block[inputs.size()]);
                c.outputs = outputs.toArray(new Block[outputs.size()]);
                c.structure = structure.toArray(new Block[structure.size()]);
                c.lastLineBlock = lastLineBlock;
                c.args = args;

                if (c.initCircuit(null, player, args)) {
                    circuits.add(c);
                    saveCircuits();
                    player.sendMessage("Activated " + c.getClass().getSimpleName() + " with " + inputs.size() + " inputs and " + outputs.size() + " outputs.");
                    return true;
                } else {
                    player.sendMessage(c.getClass().getSimpleName() + " was not activated.");
                    return false;
                }
            } catch (ClassNotFoundException ex) {
                ex.printStackTrace();
            } catch (InstantiationException ex) {
                ex.printStackTrace();
                log.warning(ex.toString());
            } catch (IllegalAccessException ex) {
                ex.printStackTrace();
                log.warning(ex.toString());
            }
        }

        // if we reached this point the circuit wasn't created.
        return false;
    }

    private void checkCircuitDestroyed(Block b, Player p) {
        Circuit destroyed = null;
        for (Circuit c : circuits) {
            for (Block s : c.structure) {
                if (s.equals(b)) {
                    destroyed = c;
                    break;
                }
            }
        }

        if (destroyed!=null) {
            p.sendMessage("You destroyed the " + destroyed.getClass().getSimpleName() + " chip.");
            destroyed.circuitDestroyed();
            circuits.remove(destroyed);
            saveCircuits();
        }
    }
}
