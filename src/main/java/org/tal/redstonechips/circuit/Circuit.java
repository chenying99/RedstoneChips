package org.tal.redstonechips.circuit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.tal.redstonechips.RedstoneChips;
import org.tal.redstonechips.util.BitSet7;
import org.tal.redstonechips.util.BitSetUtils;
import org.tal.redstonechips.util.ChunkLocation;


/**
 * Represents a RedstoneChips circuit.
 *
 * @author Tal Eisenberg
 */
public abstract class Circuit {
    /**
     * Reference to the core plugin instance.
     */
    protected RedstoneChips redstoneChips;

    /**
     * Circuit sign arguments. Any word found on the circuit sign from line 2 onward.
     */
    public String[] args;
    
    /**
     * Ordered list of input pins. 
     */
    public InputPin[] inputs;

    /**
     * Ordered list of output pins.
     */
    public OutputPin[] outputs;

    /**
     * Contains the location of any block that is part of this circuit. When any block in this array is broken the circuit is destroyed.
     * This includes the sign block, chip blocks, input blocks, output blocks and output lever blocks.
     */
    public Location[] structure;

    /**
     * Ordered list of interface blocks. Used for interaction points with the "physical" world.
     */
    public InterfaceBlock[] interfaceBlocks;

    /**
     * The location of the sign block that was used to activate the circuit.
     */
    public Location activationBlock;

    /**
     * Reference to the minecraft World this circuit was built in.
     */
    public World world;

    /**
     * List of command senders that will receive debug messages from this circuit.
     */
    private List<CommandSender> debuggers;

    /**
     * List of command senders that will receive io debug messages from this circuit.
     */
    private List<CommandSender> iodebuggers;

    /**
     * The current state of each input bit. Should be used only for monitoring. Do not change its value.
     */
    protected BitSet7 inputBits;

    /**
     * The current state of each output bit. Should be used only for monitoring. Do not change its value.
     */
    protected BitSet7 outputBits;

    /**
     * When set to true any input changes will be ignored.
     */
    public boolean disabled = false;

    /**
     * The circuit id. Set by CircuitManager.
     */
    public int id = -1;

    /**
     * An optional circuit instance name.
     */
    public String name = null;
       
    /**
     * Set to the chunk coordinates of the circuit's activation block
     */
    public ChunkLocation[] circuitChunks;

    /**
     * Initializes the circuit. 
     * Updates input pin values according to source blocks.
     * Refreshes the output pins according to input states if the circuit is stateless.
     * 
     * @param sender The sender that activated the circuit. Used for sending error or status messages after activation.
     * @param args The sign arguments of this circuit. Stored in the args field.
     * @return result of call to abstract Circuit.init() method.
     */
    public final boolean initCircuit(CommandSender sender, RedstoneChips rc) {
        this.redstoneChips = rc;

        debuggers = new ArrayList<CommandSender>();
        iodebuggers = new ArrayList<CommandSender>();

        inputBits = new BitSet7(inputs.length);
        if (outputBits==null) outputBits = new BitSet7(outputs.length);
        
        updateInputBits();
        
        boolean result = init(sender, args);

        if (disabled) disable();
        else if (result!=false && isStateless()) {
            runInputLogic();
        }

        return result;
    }

    /**
     * Called by the plugin whenever an input pin changes state.
     * If the new state is different from the previous state stored in inputBits the inputBits value is updated
     * and the inputChange(idx, newVal) method is called.
     *
     * @param idx The changed input pin index.
     * @param newVal true if the current is greater than 0.
     */
    public void stateChange(int idx, boolean newVal) {
        if (disabled) return;

        if (inputBits.get(idx)==newVal) return;

        inputBits.set(idx, newVal);

        if (hasIODebuggers()) {
            int inputInt = BitSetUtils.bitSetToUnsignedInt(inputBits, 0, inputs.length);

            String i = ChatColor.WHITE + BitSetUtils.bitSetToBinaryString(inputBits, 0, inputs.length) + " (0x" +
                    Integer.toHexString(inputInt) + ")";

            ioDebug("Input " + idx + " is " + (newVal?"on":"off")+ ": " + i + ".");
        }

        inputChange(idx, newVal);
    }

    /**
     * Resets outputs, calls circuitShutdown() and circuitDestroyed().
     */
    public void destroyCircuit() {
        circuitShutdown();
        
        for (OutputPin o : outputs) o.changeOutputState(false);
        
        circuitDestroyed();
    }
    
    /**
     * Called when an input pin state is changed.
     *
     * @param inIdx index of changed input pin. 
     * @param state The new state of the input pin.
     */
    public abstract void inputChange(int inIdx, boolean state);

    /**
     * Called after the chip is activated by a user or after the chip is loaded from file.
     * 
     * @param sender The command sender that activated the chip, or null if called on startup.
     * @param args Any words on the sign after the circuit type.
     * @return true if the init was successful, false if an error occurred.
     */
    protected abstract boolean init(CommandSender sender, String[] args);

    /**
     * Called when the plugin needs to save the circuits state to disk or when using /rcinfo.
     * The circuit should return a map containing any data needed to bring the circuit back to its current state
     * after a server restart.
     *
     * @return Map containing state data.
     */
    public Map<String,String> getInternalState() { return new HashMap<String,String>(); }

    /**
     * Called whenever the plugin is requested to save it's data. 
     */
    public void save() { }

    /**
     * Called when the plugin loads a circuit from file.
     *
     * @param state Map containing state data that was read from the file. The map should hold the same data that was returned by saveState()
     */
    public void setInternalState(Map<String,String> state) {}

    /**
     * Called when the circuit is physically destroyed.
     * Put here any necessary cleanups.
     */
    public void circuitDestroyed() {}

    /**
     * Called when the circuit is shutdown. Typically when the server shutsdown or the plugin is disabled and before a circuit is 
     * destroyed.
     */
    public void circuitShutdown() {}

    /**
     * Sets the physical state of one of the outputs.
     *
     * @param outIdx Output index. First output is 0.
     * @param state The new state of the output.
     */
    protected void sendOutput(int outIdx, boolean state) {
        outputBits.set(outIdx, state);
        if (hasIODebuggers()) {
            int outputInt = BitSetUtils.bitSetToUnsignedInt(outputBits, 0, outputs.length);

            String o;
            if (outputs.length>0)
                o = ChatColor.YELLOW + BitSetUtils.bitSetToBinaryString(outputBits, 0, outputs.length) + " (0x" +
                    Integer.toHexString(outputInt) + ")";
            else o = "";

            ioDebug("Output " + outIdx + " is " + (state?"on":"off") + ": " + o + ".");
        }

        outputs[outIdx].changeOutputState(state);
    }

    /**
     * Send an integer through a set of outputs.
     * First converts to BitSet by calling intToBitSet(), then calls sendBitSet().
     *
     * @param startOutIdx output index of first output (LSB).
     * @param length number of bits/outputs to write to.
     * @param value The integer value to send out.
     */
    protected void sendInt(int startOutIdx, int length, int value) {
        BitSet7 bits = BitSetUtils.intToBitSet(value, length);
        sendBitSet(startOutIdx, length, bits);
    }

    /**
     * Sends a BitSet object to the circuit outputs.
     *
     * @param startOutIdx First output pin that will be set.
     * @param length Number of bits to set.
     * @param bits The BitSet object to send out. Any excessive bits in the BitSet are ignored.
     */
    protected void sendBitSet(int startOutIdx, int length, BitSet7 bits) {
        for (int i=0; i<length; i++) {
            boolean b = bits.get(i);
            sendOutput(startOutIdx+i, b);
        }
    }

    /**
     * Sends a BitSet object to the circuit outputs.
     * Sends a bit to each circuit output, starting from output 0.
     *
     * @param bits BitSet object to send out.
     */
    protected void sendBitSet(BitSet7 bits) {
        sendBitSet(0, outputs.length, bits);
    }



    /**
     * Useful method for posting error messages. Sends an error message to the requested command sender using the error chat color as
     * set in the preferences file. If sender is null the message is sent to the console logger as a warning.
     * @param sender The command sender to send the message to.
     * @param message The error message.
     */
    protected void error(CommandSender sender, String message) {
        if (sender!=null) sender.sendMessage(redstoneChips.getPrefs().getErrorColor() + message);
        else redstoneChips.log(Level.WARNING, this.getClass().getSimpleName() + "> " + message);
    }

    /**
     * Useful method for posting info messages. Sends an info message to the requested command sender using the info chat color as
     * set in the preferences file. If sender is null the message is simply ignored.
     * @param sender The CommandSender to send the message to.
     * @param message The error message.
     */
    protected void info(CommandSender sender, String message) {
        if (sender!=null) sender.sendMessage(redstoneChips.getPrefs().getInfoColor() + message);
    }

    /**
     * Sends a debug message to all debugging players of this circuit, using the debug chat color preferences key.
     * Please check that hasDebuggers() returns true before processing any debug messages.
     *
     * @param message The error message.
     */
    protected void debug(String message) {        
        for (CommandSender s : debuggers)
            if (!redstoneChips.getCircuitManager().isDebuggerPaused(s)) s.sendMessage(redstoneChips.getPrefs().getDebugColor() + 
                    (name!=null?name + ": ":this.getClass().getSimpleName() + " (" + id + "): ") + message);
    }

    /**
     * Sends a debug message to all io debugging players of this circuit, using the debug chat color preferences key.
     * Please check that hasDebuggers() returns true before processing any debug messages.
     *
     * @param message The error message.
     */    
    protected void ioDebug(String message) {
        for (CommandSender s : iodebuggers)
            if (!redstoneChips.getCircuitManager().isDebuggerPaused(s)) s.sendMessage(redstoneChips.getPrefs().getDebugColor() + 
                    (name!=null?name + ": ":this.getClass().getSimpleName() + " (" + id + "): ") + message);
    }

    /**
     * Adds the command sender as a debugger for the circuit.
     *
     * @param d The command sender to add.
     * @throws IllegalArgumentException If the sender is already in the debuggers list.
     */
    public void addDebugger(CommandSender d) throws IllegalArgumentException {
        if (!debuggers.contains(d)) debuggers.add(d);
    }

    /**
     * Adds the command sender as an IO debugger for the circuit.
     * 
     * @param d The command sender to add.
     * @throws IllegalArgumentException I the sender is already in the io debuggers list.
     */
    public void addIODebugger(CommandSender d) {
        if (!iodebuggers.contains(d)) iodebuggers.add(d);
    }

    /**
     * Removes the command sender from the debuggers list.
     *
     * @param d The command sender.
     * @return true if the command sender was found on the debugger list.
     */
    public boolean removeDebugger(CommandSender d) {
        return debuggers.remove(d);
    }

    /**
     * Removes the command sender from the IO debuggers list.
     * 
     * @param d The command sender.
     * @return true if the command sender was found on the IO debugger list.
     */
    public boolean removeIODebugger(CommandSender d) {
        return iodebuggers.remove(d);
    }

    /**
     * Checks if the circuit has any debuggers waiting for debug messages. This method should be used
     * before processing any debug information to avoid wasting cpu when no debuggers are listening.
     *
     * @return True if the circuit has any debuggers.
     */
    public boolean hasDebuggers() { return !debuggers.isEmpty(); }

    /**
     * 
     * @return true if the circuit has any IO debuggers.
     */
    public boolean hasIODebuggers() { return !iodebuggers.isEmpty(); }

    /**
     *
     * @return a clone of the outputBits field.
     */
    public BitSet7 getOutputBits() { return (BitSet7)outputBits.clone(); }

    /**
     *
     * @return a clone of the inputBits field.
     */
    public BitSet7 getInputBits() { return (BitSet7)inputBits.clone(); }

    /**
     *
     * @return The name of this circuit class, as it should be typed on the chip sign.
     */
    public String getCircuitClass() {
        return this.getClass().getSimpleName();
    }

    /**
     * 
     * @return The name of the circuit class and chip id and name.
     */
    public String getChipString() {
        return getCircuitClass() + " (" + (name!=null?name + "/":"") + id + ")";
    }
    
    /**
     *
     * @return The circuit's debuggers list.
     */
    public List<CommandSender> getDebuggers() {
        return debuggers;
    }

    /**
     * 
     * @return The circuit's IO debuggers list.
     */
    public List<CommandSender> getIODebuggers() {
        return iodebuggers;
    }

    /**
     *
     * @param outputIdx The required output pin number.
     * @return The output block (gold block by default) of the specific output index.
     */
    protected Block getOutputBlock(int outputIdx) {
        Location l = outputs[outputIdx].getLocation();
        return world.getBlockAt(l);
    }

    /**
     * @param inputIdx The required input pin number.
     * @return The input block (iron block by default) of the specific input index.
     */
    protected Block getInputBlock(int inputIdx) {
        Location l = inputs[inputIdx].getLocation();
        return world.getBlockAt(l);
    }

    /**
     * Called when any of the circuit's chunks has loaded. Causes the circuit to update the state of its output levers 
     * according to the current values in outputBits.
     */
    public void circuitChunkLoaded() {
        for (InputPin i : inputs)
            i.refreshSourceBlocks();

        for (int i=0; i<outputs.length; i++)
            outputs[i].changeOutputState(outputBits.get(i));
    }

    /**
     * Update the inputBits BitSet according to the current input pin values.
     */
    public void updateInputBits() {
        for (int i=0; i<inputs.length; i++) {
            inputs[i].refreshSourceBlocks();
            inputBits.set(i, inputs[i].getPinValue());
        }
    }

    /**
     * Returns true. A stateless circuit is a circuit that will always output the same values 
     * for a set of input values.
     * A logical gate is an example of a stateless circuit while a counter is not stateless.
     * 
     * @return True if the circuit is stateless.
     */
    protected boolean isStateless() {
        return true;
    }

    /**
     * Disables or enables the chip according to the parameter.
     * 
     * @param d true to disable or false to enable.
     */
    public void setDisabled(Boolean d) {
        if (d) disable();
        else enable();
    }
    
    /**
     * Forces the circuit to stop processing input changes.
     */
    public void disable() {
        disabled = true;
        updateCircuitSign(true);
        if (hasDebuggers()) debug(redstoneChips.getPrefs().getErrorColor() + "Chip is disabled.");
    }

    /**
     * Enables the chip, allowing it to process input changes.
     */
    public void enable() {
        disabled = false;
        updateCircuitSign(true);
        if (hasDebuggers()) debug(redstoneChips.getPrefs().getErrorColor() + "Chip is enabled.");
    }
    
    /**
     *
     * @return true if the circuit's inputs are disabled.
     */
    public boolean isDisabled() { return disabled; }

    /**
     * Replaces the chip input, output and interface block materials to the currently set materials in the preferences.
     * @return The number of blocks that were replaced.
     */
    public int fixIOBlocks() {
        int blockCount = 0;

        int inputType = redstoneChips.getPrefs().getInputBlockType().getItemTypeId();
        byte inputData = redstoneChips.getPrefs().getInputBlockType().getData();

        int outputType = redstoneChips.getPrefs().getOutputBlockType().getItemTypeId();
        byte outputData = redstoneChips.getPrefs().getOutputBlockType().getData();

        int interfaceType = redstoneChips.getPrefs().getInterfaceBlockType().getItemTypeId();
        byte interfaceData = redstoneChips.getPrefs().getInterfaceBlockType().getData();

        List<ChunkLocation> chunksToUnload = new ArrayList<ChunkLocation>();
        for (ChunkLocation chunk : circuitChunks) {
            if (!chunk.isChunkLoaded()) {
                chunksToUnload.add(chunk);
                redstoneChips.getCircuitManager().workOnChunk(chunk);
            }
            
        }

        for (InputPin i : inputs) {
            Block input = i.getLocation().getBlock();

            if (input.getTypeId()!=inputType || input.getData()!=inputData) {
                input.setTypeIdAndData(inputType, inputData, false);
                blockCount++;
            }
        }

        for (OutputPin o : outputs) {
            Block output = o.getLocation().getBlock();

            if (output.getTypeId()!=outputType || output.getData()!=outputData) {
                output.setTypeIdAndData(outputType, outputData, false);
                blockCount++;
            }
        }

        for (InterfaceBlock t : interfaceBlocks) {
            Block tb = t.getLocation().getBlock();

            if (tb.getTypeId()!=interfaceType || tb.getData()!=interfaceData) {
                tb.setTypeIdAndData(interfaceType, interfaceData, false);
                blockCount++;
            }
        }

        for (ChunkLocation chunk : chunksToUnload) {
            redstoneChips.getCircuitManager().releaseChunk(chunk);
        }

        return blockCount;
    }

    /**
     * Updates the text and color of the 1st line of the circuit activation sign.
     * @param activated When true the class name is colored in the selected signColor preference key. When false the color is removed.
     */
    public void updateCircuitSign(boolean activated) {
        if (!ChunkLocation.fromLocation(activationBlock).isChunkLoaded()) return;
        
        BlockState state = activationBlock.getBlock().getState();
        if (!(state instanceof Sign)) return;

        final Sign sign = (Sign)state;
        if (sign==null) return;
        String line;
        if (activated) {
            String signColor;
            if (isDisabled()) signColor = "8";
            else signColor = redstoneChips.getPrefs().getSignColor();
            line = (char)167 + signColor + this.getCircuitClass();
        } else {
            line = this.getCircuitClass();
        }

        try {
            if (!line.equals(sign.getLine(0))) {
                sign.setLine(0, line);

                redstoneChips.getServer().getScheduler().scheduleSyncDelayedTask(redstoneChips, new Runnable() {
                    @Override
                    public void run() {
                        sign.update();
                    }
                });
            }
        } catch (NullPointerException ne) { }
    }

    private void runInputLogic() {
        for (int i=0; i<inputs.length; i++)
            this.inputChange(i, inputBits.get(i));
    }

    /**
     * Checks whether all of the circuit's blocks are in place.
     * Makes sure that each output lever block of lever material, checks that the activation sign is in place
     * and that none of the circuit's structure blocks are air.
     * 
     * @return True if the test passed.
     */
    public boolean checkIntegrity() {
        if (world.getBlockTypeIdAt(activationBlock)!=Material.WALL_SIGN.getId()) {
            redstoneChips.log(Level.WARNING, "Circuit " + id + ": Sign is missing at " + activationBlock.getBlockX() + "," + activationBlock.getBlockY() + ", " + activationBlock.getBlockZ() + ".");
            return false;
        } 
        
        for (Location s : structure) {
            if (!s.equals(activationBlock)) {
                if (world.getBlockTypeIdAt(s)==Material.AIR.getId()) {
                    redstoneChips.log(Level.WARNING, "Circuit " + id + ": Chip block is missing at " + s.getBlockX() + "," + s.getBlockY() + ", " + s.getBlockZ() + ".");
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Turns off all outputs.
     */
    public void resetOutputs() {
        for (OutputPin o : outputs)            
            o.changeOutputState(false);
    }

    /**
     * 
     * @return an instance of the RedstoneChips plugin.
     */
    public RedstoneChips getPlugin() {
        return redstoneChips;
    }

    /**
     * Initalizes the output buffer. Can only be used before calling initCircuit().
     * @param bits 
     */
    public void setOutputBits(BitSet7 bits) {
        if (outputBits==null) outputBits = bits;
        else throw new RuntimeException("Trying to set outputBits, but it's already set.");
    }
}
