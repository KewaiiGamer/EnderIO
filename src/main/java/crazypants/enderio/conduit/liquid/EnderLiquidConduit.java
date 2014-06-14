package crazypants.enderio.conduit.liquid;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import crazypants.enderio.Config;
import crazypants.enderio.EnderIO;
import crazypants.enderio.conduit.AbstractConduitNetwork;
import crazypants.enderio.conduit.ConduitUtil;
import crazypants.enderio.conduit.ConnectionMode;
import crazypants.enderio.conduit.IConduit;
import crazypants.enderio.conduit.RaytraceResult;
import crazypants.enderio.conduit.geom.CollidableComponent;
import crazypants.render.IconUtil;
import crazypants.util.BlockCoord;

public class EnderLiquidConduit extends AbstractLiquidConduit {

  public static final String ICON_KEY = "enderio:liquidConduitEnder";
  public static final String ICON_CORE_KEY = "enderio:liquidConduitCoreEnder";
  public static final String ICON_EXTRACT_KEY = "enderio:liquidConduitAdvancedInput";
  public static final String ICON_INSERT_KEY = "enderio:liquidConduitAdvancedOutput";

  static final Map<String, IIcon> ICONS = new HashMap<String, IIcon>();

  @SideOnly(Side.CLIENT)
  public static void initIcons() {
    IconUtil.addIconProvider(new IconUtil.IIconProvider() {

      @Override
      public void registerIcons(IIconRegister register) {
        ICONS.put(ICON_KEY, register.registerIcon(ICON_KEY));
        ICONS.put(ICON_CORE_KEY, register.registerIcon(ICON_CORE_KEY));
        ICONS.put(ICON_EXTRACT_KEY, register.registerIcon(ICON_EXTRACT_KEY));
        ICONS.put(ICON_INSERT_KEY, register.registerIcon(ICON_INSERT_KEY));
      }

      @Override
      public int getTextureType() {
        return 0;
      }

    });
  }

  private EnderLiquidConduitNetwork network;
  private int ticksSinceFailedExtract;

  @Override
  public ItemStack createItem() {
    return new ItemStack(EnderIO.itemLiquidConduit, 1, 2);
  }

  @Override
  public boolean onBlockActivated(EntityPlayer player, RaytraceResult res, List<RaytraceResult> all) {
    if(player.getCurrentEquippedItem() == null) {
      return false;
    }

    if(ConduitUtil.isToolEquipped(player)) {

      if(!getBundle().getEntity().getWorldObj().isRemote) {

        if(res != null && res.component != null) {

          ForgeDirection connDir = res.component.dir;
          ForgeDirection faceHit = ForgeDirection.getOrientation(res.movingObjectPosition.sideHit);

          if(connDir == ForgeDirection.UNKNOWN || connDir == faceHit) {

            if(getConectionMode(faceHit) == ConnectionMode.DISABLED) {
              setConnectionMode(faceHit, getNextConnectionMode(faceHit));
              return true;
            }

            BlockCoord loc = getLocation().getLocation(faceHit);
            ILiquidConduit n = ConduitUtil.getConduit(getBundle().getEntity().getWorldObj(), loc.x, loc.y, loc.z, ILiquidConduit.class);
            if(n == null) {
              return false;
            }
            if(!(n instanceof EnderLiquidConduit)) {
              return false;
            }
            return ConduitUtil.joinConduits(this, faceHit);
          } else if(containsExternalConnection(connDir)) {
            // Toggle extraction mode
            setConnectionMode(connDir, getNextConnectionMode(connDir));
          } else if(containsConduitConnection(connDir)) {
            ConduitUtil.disconectConduits(this, connDir);

          }
        }
      }
      return true;
    }
    return false;
  }

  @Override
  public AbstractConduitNetwork<?, ?> getNetwork() {
    return network;
  }

  @Override
  public boolean setNetwork(AbstractConduitNetwork<?, ?> network) {
    if(network == null) {
      this.network = null;
      return true;
    }
    if(!(network instanceof EnderLiquidConduitNetwork)) {
      return false;
    }
    this.network = (EnderLiquidConduitNetwork) network;
    for(ForgeDirection dir : externalConnections) {
      this.network.connectionChanged(this, dir);
    }
    
    return true;
  }

  @Override
  public IIcon getTextureForState(CollidableComponent component) {
    if(component.dir == ForgeDirection.UNKNOWN) {
      return ICONS.get(ICON_CORE_KEY);
    }
    return ICONS.get(ICON_KEY);
  }

  public IIcon getTextureForInputMode() {
    return ICONS.get(ICON_EXTRACT_KEY);
  }

  public IIcon getTextureForOutputMode() {
    return ICONS.get(ICON_INSERT_KEY);
  }

  @Override
  public IIcon getTransmitionTextureForState(CollidableComponent component) {
    return null;
  }

  @Override
  public boolean canConnectToConduit(ForgeDirection direction, IConduit con) {
    if(!super.canConnectToConduit(direction, con)) {
      return false;
    }
    if(!(con instanceof EnderLiquidConduit)) {
      return false;
    }
    return true;
  }

  @Override
  public void setConnectionMode(ForgeDirection dir, ConnectionMode mode) {
    super.setConnectionMode(dir, mode);
    refreshConnections(dir);
  }

  private void refreshConnections(ForgeDirection dir) {
    if(network == null) {      
      return;
    }
    network.connectionChanged(this, dir);
  }

  @Override
  public void externalConnectionAdded(ForgeDirection fromDirection) {
    super.externalConnectionAdded(fromDirection);
    refreshConnections(fromDirection);
  }

  @Override
  public void externalConnectionRemoved(ForgeDirection fromDirection) {
    super.externalConnectionRemoved(fromDirection);
    refreshConnections(fromDirection);
  }

  @Override
  public void updateEntity(World world) {
    super.updateEntity(world);
    if(world.isRemote) {
      return;
    }
    doExtract();
  }

  private void doExtract() {
    BlockCoord loc = getLocation();
    if(!hasConnectionMode(ConnectionMode.INPUT)) {
      return;
    }
    if(network == null) {
      return;
    }

    // assume failure, reset to 0 if we do extract
    ticksSinceFailedExtract++;
    if(ticksSinceFailedExtract > 25 && ticksSinceFailedExtract % 10 != 0) {
      // after 25 ticks of failing, only check every 10 ticks
      return;
    }

    for (ForgeDirection dir : externalConnections) {
      if(autoExtractForDir(dir)) {
        if(network.extractFrom(this, dir)) {
          ticksSinceFailedExtract = 0;
        }
      }
    }

  }

  //Fluid API

  @Override
  public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
    if(network == null || !getConectionMode(from).acceptsInput()) {
      return 0;
    }
    return network.fillFrom(this, from, resource, doFill);
  }

  @Override
  public boolean canFill(ForgeDirection from, Fluid fluid) {
    if(network == null) {
      return false;
    }
    return getConectionMode(from).acceptsInput();
  }

  @Override
  public boolean canDrain(ForgeDirection from, Fluid fluid) {
    return false;
  }

  @Override
  public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
    return null;
  }

  @Override
  public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
    return null;
  }

  @Override
  public FluidTankInfo[] getTankInfo(ForgeDirection from) {
    if(network == null) {
      return new FluidTankInfo[0];
    }
    return network.getTankInfo(this, from);
  }

}
