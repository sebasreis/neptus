/*
 * Copyright (c) 2004-2016 Universidade do Porto - Faculdade de Engenharia
 * Laboratório de Sistemas e Tecnologia Subaquática (LSTS)
 * All rights reserved.
 * Rua Dr. Roberto Frias s/n, sala I203, 4200-465 Porto, Portugal
 *
 * This file is part of Neptus, Command and Control Framework.
 *
 * Commercial Licence Usage
 * Licencees holding valid commercial Neptus licences may use this file
 * in accordance with the commercial licence agreement provided with the
 * Software or, alternatively, in accordance with the terms contained in a
 * written agreement between you and Universidade do Porto. For licensing
 * terms, conditions, and further information contact lsts@fe.up.pt.
 *
 * European Union Public Licence - EUPL v.1.1 Usage
 * Alternatively, this file may be used under the terms of the EUPL,
 * Version 1.1 only (the "Licence"), appearing in the file LICENSE.md
 * included in the packaging of this file. You may not use this work
 * except in compliance with the Licence. Unless required by applicable
 * law or agreed to in writing, software distributed under the Licence is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific
 * language governing permissions and limitations at
 * https://www.lsts.pt/neptus/licence.
 *
 * For more information please see <http://lsts.fe.up.pt/neptus>.
 *
 * Author: tsmarques
 * 26 Feb 2016
 */
package pt.lsts.neptus.plugins.mvplanning.interfaces;

import pt.lsts.neptus.plugins.mvplanning.PlanTask;
import pt.lsts.neptus.plugins.mvplanning.VehicleAwareness;
import pt.lsts.neptus.plugins.update.IPeriodicUpdates;
import pt.lsts.neptus.plugins.update.PeriodicUpdatesService;
import pt.lsts.neptus.util.ByteUtil;

import java.util.concurrent.Future;

import pt.lsts.imc.PlanControl;
import pt.lsts.imc.PlanControl.OP;
import pt.lsts.imc.state.ImcSystemState;
import pt.lsts.imc.PlanDB;
import pt.lsts.imc.PlanSpecification;
import pt.lsts.neptus.comm.IMCSendMessageUtils;
import pt.lsts.neptus.comm.manager.imc.ImcMsgManager.SendResult;
import pt.lsts.neptus.comm.manager.imc.ImcSystem;
import pt.lsts.neptus.comm.manager.imc.ImcSystemsHolder;

/**
 * @author tsmarques
 *
 */
public abstract class AbstractAllocator implements IPeriodicUpdates {
    private boolean isPeriodic;
    private long period = 5000;
    private boolean listenToEvents;
    protected ConsoleAdapter console;

    public AbstractAllocator(boolean isPeriodic, boolean listenToEvents, ConsoleAdapter console) {
        this.console = console;
        setPeriodic(isPeriodic);
        setListenToEvents(listenToEvents);
    }


    /**
     * Called by {@link pt.lsts.neptus.plugins.mvplanning.PlanAllocator} when a new plan/objective is added by the user
     * */
    public abstract void addNewPlan(PlanTask ptask);


    /**
     * Set the object used to keep track of available
     * and unavailable vehicles.
     * */
    public abstract void setVehicleAwareness(VehicleAwareness vawareness);


    /**
     * Run the allocation algorithm. If the allocator
     * is set as periodic, this method will be
     *  called periodically. 
     * */
    public abstract void doAllocation();


    /**
     * Send the actual plan to a vehicle. 
     * */
    protected boolean allocateTo(String vehicle, PlanTask ptask) {
        /* TODO: Implement using sendMessage() from ConsoleAdapter */
        try {
            int reqId = IMCSendMessageUtils.getNextRequestId();

            PlanDB pdb = new PlanDB();
            PlanSpecification plan = ptask.getPlanSpecification();

            pdb.setType(PlanDB.TYPE.REQUEST);
            pdb.setOp(PlanDB.OP.SET);
            pdb.setRequestId(reqId);
            pdb.setPlanId(ptask.getPlanId());
            pdb.setArg(plan);
            pdb.setInfo("Plan allocated by [mvplanning/PlanAllocator]");

            Future<SendResult> res = console.sendMessageReliably(vehicle, pdb);
            boolean planSent = false;
            SendResult sendRes = res.get();


            if(sendRes == SendResult.UNCERTAIN_DELIVERY)
                planSent = confirmPlanAllocated(vehicle, ptask);

            if(sendRes == SendResult.SUCCESS || planSent) {
                reqId = IMCSendMessageUtils.getNextRequestId();
                PlanControl pc = new PlanControl();
                pc.setType(PlanControl.TYPE.REQUEST);
                pc.setRequestId(reqId);
                pc.setPlanId(ptask.getPlanId());
                pc.setOp(OP.START);

                Future<SendResult> cmdRes = console.sendMessageReliably(vehicle, pc);
                boolean cmdSent = (cmdRes.get() == SendResult.SUCCESS);

                return cmdSent;
            }
            else
                return false;
        }
        catch(Exception e) {
            e.printStackTrace();
            System.out.println("[mvplanning/PlanAllocator]: Failed to allocate plan " + ptask.getPlanId() + " to " + vehicle);
            return false;
        }
    }

    private boolean confirmPlanAllocated(String vehicle, PlanTask ptask) {
        ImcSystem sys = ImcSystemsHolder.getSystemWithName(vehicle);
        byte[] remoteMD5 = sys.getPlanDBControl().getRemoteState().getStoredPlans().get(ptask.getPlanId()).getMd5();

        if(remoteMD5 == null)
            return false;

        return ByteUtil.equal(ptask.getMd5(), remoteMD5);
    }

    /**
     * Set if the allocator is periodic */    
    private void setPeriodic(boolean isPeriodic) {
        this.isPeriodic = isPeriodic;

        if(isPeriodic)
            PeriodicUpdatesService.register(this);
    }

    /**
     * Set <strong>true</strong> to register the allocator to
     * events
     * */
    private void setListenToEvents(boolean listenToEvents) {
        this.listenToEvents = listenToEvents;
    }

    /**
     * Period between updates, in milliseconds
     * */
    public void setUpdatePeriod(long period) {
        this.period = period;
    }

    /**
     * If this allocator is periodic
     * */
    public boolean isPeriodic() {
        return isPeriodic;
    }

    /**
     * If this allocator is listening to
     * events
     * */
    public boolean listenToEvents() {
        return listenToEvents;
    }

    @Override
    public long millisBetweenUpdates() {
        return period;
    }

    @Override
    public boolean update() {
        doAllocation();
        return isPeriodic;
    }
}
