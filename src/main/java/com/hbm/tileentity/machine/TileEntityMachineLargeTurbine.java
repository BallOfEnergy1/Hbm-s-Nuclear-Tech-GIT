package com.hbm.tileentity.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.hbm.blocks.BlockDummyable;
import com.hbm.handler.CompatHandler;
import com.hbm.interfaces.IFluidAcceptor;
import com.hbm.interfaces.IFluidContainer;
import com.hbm.interfaces.IFluidSource;
import com.hbm.inventory.container.ContainerMachineLargeTurbine;
import com.hbm.inventory.fluid.FluidType;
import com.hbm.inventory.fluid.Fluids;
import com.hbm.inventory.fluid.tank.FluidTank;
import com.hbm.inventory.fluid.trait.FT_Coolable;
import com.hbm.inventory.fluid.trait.FT_Coolable.CoolingType;
import com.hbm.inventory.gui.GUIMachineLargeTurbine;
import com.hbm.lib.Library;
import com.hbm.main.MainRegistry;
import com.hbm.sound.AudioWrapper;
import com.hbm.tileentity.IGUIProvider;
import com.hbm.tileentity.TileEntityMachineBase;
import com.hbm.util.CompatEnergyControl;
import com.hbm.util.fauxpointtwelve.DirPos;

import api.hbm.energymk2.IEnergyProviderMK2;
import api.hbm.fluid.IFluidStandardTransceiver;
import api.hbm.tile.IInfoProviderEC;
import cpw.mods.fml.common.Optional;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.ManagedPeripheral;
import li.cil.oc.api.network.SimpleComponent;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

@Optional.InterfaceList({
		@Optional.Interface(iface = "li.cil.oc.api.network.SimpleComponent", modid = "OpenComputers"),
		@Optional.Interface(iface = "li.cil.oc.api.network.ManagedPeripheral", modid = "OpenComputers")
})
public class TileEntityMachineLargeTurbine extends TileEntityMachineBase implements IFluidContainer, IFluidAcceptor, IFluidSource, IEnergyProviderMK2, IFluidStandardTransceiver, IGUIProvider, SimpleComponent, ManagedPeripheral, IInfoProviderEC {

	public long power;
	public static final long maxPower = 100000000;
	public int age = 0;
	public List<IFluidAcceptor> list2 = new ArrayList();
	public FluidTank[] tanks;
	protected double[] info = new double[3];
	
	private boolean shouldTurn;
	public float rotor;
	public float lastRotor;
	public float fanAcceleration = 0F;

	private AudioWrapper audio;
	private float audioDesync;
	
	public TileEntityMachineLargeTurbine() {
		super(7);
		
		tanks = new FluidTank[2];
		tanks[0] = new FluidTank(Fluids.STEAM, 512000, 0);
		tanks[1] = new FluidTank(Fluids.SPENTSTEAM, 10240000, 1);

		Random rand = new Random();
		audioDesync = rand.nextFloat() * 0.05F;
	}

	@Override
	public String getName() {
		return "container.machineLargeTurbine";
	}

	@Override
	public void updateEntity() {
		
		if(!worldObj.isRemote) {
			
			this.info = new double[3];
			
			age++;
			if(age >= 2) {
				age = 0;
			}
			
			fillFluidInit(tanks[1].getTankType());
			
			ForgeDirection dir = ForgeDirection.getOrientation(this.getBlockMetadata() - BlockDummyable.offset);
			this.tryProvide(worldObj, xCoord + dir.offsetX * -4, yCoord, zCoord + dir.offsetZ * -4, dir.getOpposite());
			for(DirPos pos : getConPos()) this.trySubscribe(tanks[0].getTankType(), worldObj, pos.getX(), pos.getY(), pos.getZ(), pos.getDir());
			for(DirPos pos : getConPos()) this.sendFluid(tanks[1], worldObj, pos.getX(), pos.getY(), pos.getZ(), pos.getDir());

			tanks[0].setType(0, 1, slots);
			tanks[0].loadTank(2, 3, slots);
			power = Library.chargeItemsFromTE(slots, 4, power, maxPower);
			
			boolean operational = false;
			
			FluidType in = tanks[0].getTankType();
			boolean valid = false;
			if(in.hasTrait(FT_Coolable.class)) {
				FT_Coolable trait = in.getTrait(FT_Coolable.class);
				double eff = trait.getEfficiency(CoolingType.TURBINE); //100% efficiency
				if(eff > 0) {
					tanks[1].setTankType(trait.coolsTo);
					int inputOps = (int) Math.floor(tanks[0].getFill() / trait.amountReq); //amount of cycles possible with the entire input buffer
					int outputOps = (tanks[1].getMaxFill() - tanks[1].getFill()) / trait.amountProduced; //amount of cycles possible with the output buffer's remaining space
					int cap = (int) Math.ceil(tanks[0].getFill() / trait.amountReq / 5F); //amount of cycles by the "at least 20%" rule
					int ops = Math.min(inputOps, Math.min(outputOps, cap)); //defacto amount of cycles
					tanks[0].setFill(tanks[0].getFill() - ops * trait.amountReq);
					tanks[1].setFill(tanks[1].getFill() + ops * trait.amountProduced);
					this.power += (ops * trait.heatEnergy * eff);
					info[0] = ops * trait.amountReq;
					info[1] = ops * trait.amountProduced;
					info[2] = ops * trait.heatEnergy * eff;
					valid = true;
					operational = ops > 0;
				}
			}
			if(!valid) tanks[1].setTankType(Fluids.NONE);
			if(power > maxPower) power = maxPower;
			
			tanks[1].unloadTank(5, 6, slots);
			
			for(int i = 0; i < 2; i++)
				tanks[i].updateTank(xCoord, yCoord, zCoord, worldObj.provider.dimensionId);
			
			NBTTagCompound data = new NBTTagCompound();
			data.setLong("power", power);
			data.setBoolean("operational", operational);
			this.networkPack(data, 50);
		} else {
			this.lastRotor = this.rotor;
			this.rotor += this.fanAcceleration;
				
			if(this.rotor >= 360) {
				this.rotor -= 360;
				this.lastRotor -= 360;
			}
			
			if(shouldTurn) {
				// Fan accelerates with a random offset to ensure the audio doesn't perfectly align, makes for a more pleasant hum
				this.fanAcceleration = Math.max(0F, Math.min(15F, this.fanAcceleration += 0.075F + audioDesync));

				if(audio == null) {
					audio = MainRegistry.proxy.getLoopedSound("hbm:block.largeTurbineRunning", xCoord, yCoord, zCoord, 1.0F, 10F, 1.0F);
					audio.startSound();
				}

				float turbineSpeed = this.fanAcceleration / 15F;
				audio.updateVolume(getVolume(0.4f * turbineSpeed));
				audio.updatePitch(0.25F + 0.75F * turbineSpeed);
			} else {
				this.fanAcceleration = Math.max(0F, Math.min(15F, this.fanAcceleration -= 0.1F));
				
				if(audio != null) {
					if(this.fanAcceleration > 0) {
						float turbineSpeed = this.fanAcceleration / 15F;
						audio.updateVolume(getVolume(0.4f * turbineSpeed));
						audio.updatePitch(0.25F + 0.75F * turbineSpeed);
					} else {
						audio.stopSound();
						audio = null;
					}
				}
			}
		}
	}
	
	protected DirPos[] getConPos() {
		ForgeDirection dir = ForgeDirection.getOrientation(this.getBlockMetadata() - BlockDummyable.offset);
		ForgeDirection rot = dir.getRotation(ForgeDirection.UP);
		return new DirPos[] {
				new DirPos(xCoord + rot.offsetX * 2, yCoord, zCoord + rot.offsetZ * 2, rot),
				new DirPos(xCoord - rot.offsetX * 2, yCoord, zCoord - rot.offsetZ * 2, rot.getOpposite()),
				new DirPos(xCoord + dir.offsetX * 2, yCoord, zCoord + dir.offsetZ * 2, dir)
		};
	}
	
	public void networkUnpack(NBTTagCompound data) {
		super.networkUnpack(data);
		
		this.power = data.getLong("power");
		this.shouldTurn = data.getBoolean("operational");
	}
	
	public long getPowerScaled(int i) {
		return (power * i) / maxPower;
	}
	
	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);
		tanks[0].readFromNBT(nbt, "water");
		tanks[1].readFromNBT(nbt, "steam");
		power = nbt.getLong("power");
	}
	
	@Override
	public void writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);
		tanks[0].writeToNBT(nbt, "water");
		tanks[1].writeToNBT(nbt, "steam");
		nbt.setLong("power", power);
	}

	@Override
	public void fillFluidInit(FluidType type) {
		
		ForgeDirection dir = ForgeDirection.getOrientation(this.getBlockMetadata() - BlockDummyable.offset);
		dir = dir.getRotation(ForgeDirection.UP);

		fillFluid(xCoord + dir.offsetX * 2, yCoord, zCoord + dir.offsetZ * 2, getTact(), type);
		fillFluid(xCoord + dir.offsetX * -2, yCoord, zCoord + dir.offsetZ * -2, getTact(), type);
	}

	@Override
	public void fillFluid(int x, int y, int z, boolean newTact, FluidType type) {
		Library.transmitFluid(x, y, z, newTact, this, worldObj, type);
	}
	
	@Override
	public boolean getTact() {
		if(age == 0)
		{
			return true;
		}
		
		return false;
	}

	@Override
	public void setFluidFill(int i, FluidType type) {
		if(type.name().equals(tanks[0].getTankType().name()))
			tanks[0].setFill(i);
		else if(type.name().equals(tanks[1].getTankType().name()))
			tanks[1].setFill(i);
	}

	@Override
	public int getFluidFill(FluidType type) {
		if(type.name().equals(tanks[0].getTankType().name()))
			return tanks[0].getFill();
		else if(type.name().equals(tanks[1].getTankType().name()))
			return tanks[1].getFill();
		
		return 0;
	}

	@Override
	public int getMaxFluidFill(FluidType type) {
		if(type.name().equals(tanks[0].getTankType().name()))
			return tanks[0].getMaxFill();
		
		return 0;
	}

	@Override
	public void setFillForSync(int fill, int index) {
		if(index < 2 && tanks[index] != null)
			tanks[index].setFill(fill);
	}

	@Override
	public void setTypeForSync(FluidType type, int index) {
		if(index < 2 && tanks[index] != null)
			tanks[index].setTankType(type);
	}
	
	@Override
	public List<IFluidAcceptor> getFluidList(FluidType type) {
		return list2;
	}
	
	@Override
	public void clearFluidList(FluidType type) {
		list2.clear();
	}

	@Override
	public long getPower() {
		return power;
	}

	@Override
	public void setPower(long i) {
		this.power = i;
	}

	@Override
	public long getMaxPower() {
		return this.maxPower;
	}
	
	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		return TileEntity.INFINITE_EXTENT_AABB;
	}
	
	@Override
	@SideOnly(Side.CLIENT)
	public double getMaxRenderDistanceSquared() {
		return 65536.0D;
	}

	@Override
	public FluidTank[] getAllTanks() {
		return tanks;
	}

	@Override
	public FluidTank[] getSendingTanks() {
		return new FluidTank[] {tanks[1]};
	}

	@Override
	public FluidTank[] getReceivingTanks() {
		return new FluidTank[] {tanks[0]};
	}
	
	@Override
	public void onChunkUnload() {
		super.onChunkUnload();
		
		if(audio != null) {
			audio.stopSound();
			audio = null;
		}
	}

	@Override
	public void invalidate() {
		super.invalidate();

		if(audio != null) {
			audio.stopSound();
			audio = null;
		}
	}

	@Override
	public String getComponentName() {
		return CompatHandler.Compats.TURBINE.name;
	}

	@Override
	@Optional.Method(modid = "OpenComputers")
	public String[] methods() {
		return new String[] {
				"getFluid",
				"getType",
				"setType",
				"getInfo"
		};
	}

	public static String[] callbacks() {
		return new String[] {
				"getFluid",
				"getType",
				"setType",
				"getInfo"
		};
	}

	@Override
	@Optional.Method(modid = "OpenComputers")
	public Object[] invoke(String method, Context context, Arguments args) throws Exception {
		switch(method) {
			case ("getFluid"):
				return new Object[] {tanks[0].getFill(), tanks[1].getFill(), tanks[1].getFill(), tanks[1].getMaxFill()};
			case ("getType"):
				return CompatHandler.steamTypeToInt(tanks[1].getTankType());
			case ("setType"):
				tanks[0].setTankType(CompatHandler.intToSteamType(args.checkInteger(0)));
				return new Object[] {true};
			case ("getInfo"):
				return new Object[] {tanks[0].getFill(), tanks[0].getMaxFill(), tanks[1].getFill(), tanks[1].getMaxFill(), CompatHandler.steamTypeToInt(tanks[0].getTankType())};
		}
		throw new NoSuchMethodException();
	}

	@Override
	public Container provideContainer(int ID, EntityPlayer player, World world, int x, int y, int z) {
		return new ContainerMachineLargeTurbine(player.inventory, this);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public GuiScreen provideGUI(int ID, EntityPlayer player, World world, int x, int y, int z) {
		return new GUIMachineLargeTurbine(player.inventory, this);
	}

	@Override
	public void provideExtraInfo(NBTTagCompound data) {
		data.setBoolean(CompatEnergyControl.B_ACTIVE, info[1] > 0);
		data.setDouble(CompatEnergyControl.D_CONSUMPTION_MB, info[0]);
		data.setDouble(CompatEnergyControl.D_OUTPUT_MB, info[1]);
		data.setDouble(CompatEnergyControl.D_OUTPUT_HE, info[2]);
	}
}
