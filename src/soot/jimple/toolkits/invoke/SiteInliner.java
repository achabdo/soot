/* Soot - a J*va Optimization Framework
 * Copyright (C) 1999 Patrick Lam, Raja Vallee-Rai
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

/*
 * Modified by the Sable Research Group and others 1997-1999.
 * See the 'credits' file distributed with Soot for the complete list of
 * contributors.  (Soot is distributed at http://www.sable.mcgill.ca/soot)
 */

package soot.jimple.toolkits.invoke;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import soot.Body;
import soot.Hierarchy;
import soot.Local;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Trap;
import soot.TrapManager;
import soot.Unit;
import soot.UnitBox;
import soot.Value;
import soot.ValueBox;
import soot.jimple.AssignStmt;
import soot.jimple.CaughtExceptionRef;
import soot.jimple.IdentityRef;
import soot.jimple.IdentityStmt;
import soot.jimple.IfStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.NullConstant;
import soot.jimple.ParameterRef;
import soot.jimple.ReturnStmt;
import soot.jimple.ReturnVoidStmt;
import soot.jimple.Stmt;
import soot.jimple.ThisRef;
import soot.jimple.toolkits.invoke.SynchronizerManager;
import soot.jimple.toolkits.scalar.LocalNameStandardizer;
import soot.util.Chain;

/** Provides methods to inline a given invoke site. */
public class SiteInliner {


    /**
     * Inlines the given site. Note that this method does not actually check if it's safe (with respect to access modifiers and
     * special invokes) for it to be inlined. That functionality is handled by the InlinerSafetyManager.
     *
     */
    public static List inlineSite(SootMethod inlinee, Stmt toInline, SootMethod container) {

        Hierarchy hierarchy = Scene.v().getActiveHierarchy();

        JimpleBody containerB = (JimpleBody) container.getActiveBody();
        Chain<Unit> containerUnits = containerB.getUnits();

        Body inlineeB = inlinee.getActiveBody();
        Chain<Unit> inlineeUnits = inlineeB.getUnits();

        InvokeExpr ie = toInline.getInvokeExpr();

        Value thisToAdd = null;
        if (ie instanceof InstanceInvokeExpr) {
            thisToAdd = ((InstanceInvokeExpr) ie).getBase();
        }

        // Add synchronizing stuff.
        {
            if (inlinee.isSynchronized()) {
                // Need to get the class object if ie is a static invoke.
                if (ie instanceof InstanceInvokeExpr) {
                    SynchronizerManager.v().synchronizeStmtOn(toInline, containerB, (Local) ((InstanceInvokeExpr) ie).getBase());
                } else {
                    // If we're in an interface, we must be in a
                    // <clinit> method, which surely needs no
                    // synchronization.
                    if (!container.getDeclaringClass().isInterface()) {
                        // Whew!
                        Local l = SynchronizerManager.v().addStmtsToFetchClassBefore(containerB, toInline);
                        SynchronizerManager.v().synchronizeStmtOn(toInline, containerB, l);
                    }
                }
            }
        }

        Stmt exitPoint = (Stmt) containerUnits.getSuccOf(toInline);


        // First, clone all of the inlinee's units & locals.
        HashMap<Local, Local> oldLocalsToNew = new HashMap<Local, Local>();
        HashMap<Stmt, Stmt> oldUnitsToNew = new HashMap<Stmt, Stmt>();
        {
            Stmt cursor = toInline;
            for (Iterator<Unit> currIt = inlineeUnits.iterator(); currIt.hasNext();) {
                final Stmt curr = (Stmt) currIt.next();
                Stmt currPrime = (Stmt) curr.clone();
                if (currPrime == null) {
                    throw new RuntimeException("getting null from clone!");
                }
                currPrime.addAllTagsOf(curr);

                containerUnits.insertAfter(currPrime, cursor);
                cursor = currPrime;

                oldUnitsToNew.put(curr, currPrime);
            }

            for (Iterator<Local> lIt = inlineeB.getLocals().iterator(); lIt.hasNext();) {

                final Local l = lIt.next();
                Local lPrime = (Local) l.clone();
                if (lPrime == null) {
                    throw new RuntimeException("getting null from local clone!");
                }

                containerB.getLocals().add(lPrime);
                oldLocalsToNew.put(l, lPrime);
            }
        }

        // Backpatch the newly-inserted units using newly-constructed maps.
        {
            Iterator<Unit> it = containerUnits.iterator(containerUnits.getSuccOf(toInline), containerUnits.getPredOf(exitPoint));

            while (it.hasNext()) {
                Stmt patchee = (Stmt) it.next();

                Iterator localsToPatch = patchee.getUseAndDefBoxes().iterator();
                while (localsToPatch.hasNext()){
                    ValueBox box = (ValueBox)localsToPatch.next();

                    if (!(box.getValue() instanceof Local)) {
                        continue;
                    }

                    Local lPrime = oldLocalsToNew.get(box.getValue());
                    if (lPrime != null) {
                        box.setValue(lPrime);
                    } else {
                        throw new RuntimeException("local has no clone!");
                    }
                }

                for (UnitBox box : patchee.getUnitBoxes()) {
                    Unit uPrime = (oldUnitsToNew.get(box.getUnit()));
                    if (uPrime != null) {
                        box.setUnit(uPrime);
                    } else {
                        throw new RuntimeException("inlined stmt has no clone!");
                    }
                }
            }
        }

        // Copy & backpatch the traps; preserve their same order.
        {
            Trap prevTrap = null;
            for (Trap t : inlineeB.getTraps()) {
                Stmt newBegin = oldUnitsToNew.get(t.getBeginUnit()), newEnd = oldUnitsToNew.get(t.getEndUnit()),
                        newHandler = oldUnitsToNew.get(t.getHandlerUnit());

                if (newBegin == null || newEnd == null || newHandler == null) {
                    throw new RuntimeException("couldn't map trap!");
                }

                Trap trap = Jimple.v().newTrap(t.getException(), newBegin, newEnd, newHandler);
                if (prevTrap == null) {
                    containerB.getTraps().addFirst(trap);
                } else {
                    containerB.getTraps().insertAfter(trap, prevTrap);
                }
                prevTrap = trap;
            }
        }

        // Handle identity stmt's and returns.
        {
            Iterator<Unit> it = containerUnits.iterator(containerUnits.getSuccOf(toInline), containerUnits.getPredOf(exitPoint));
            ArrayList<Unit> cuCopy = new ArrayList<Unit>();

            while (it.hasNext()) {
                cuCopy.add(it.next());
            }

            for (Unit u : cuCopy) {
                Stmt s = (Stmt) u;

                if (s instanceof IdentityStmt) {
                    IdentityRef rhs = (IdentityRef) ((IdentityStmt) s).getRightOp();
                    if (rhs instanceof CaughtExceptionRef) {
                        continue;
                    } else if (rhs instanceof ThisRef) {
                        if (!(ie instanceof InstanceInvokeExpr)) {
                            throw new RuntimeException("thisref with no receiver!");
                        }

                        containerUnits.swapWith(s, Jimple.v().newAssignStmt(((IdentityStmt) s).getLeftOp(), thisToAdd));
                    } else if (rhs instanceof ParameterRef) {
                        ParameterRef pref = (ParameterRef) rhs;
                        containerUnits.swapWith(s, Jimple.v().newAssignStmt(((IdentityStmt) s).getLeftOp(), ie.getArg(pref.getIndex())));
                    }
                } else if (s instanceof ReturnStmt) {
                    if (toInline instanceof InvokeStmt) {
                        // munch, munch.
                        containerUnits.swapWith(s, Jimple.v().newGotoStmt(exitPoint));
                        continue;
                    }

                    if (!(toInline instanceof AssignStmt)) {
                        throw new RuntimeException("invoking stmt neither InvokeStmt nor AssignStmt!??!?!");
                    }
                    Value ro = ((ReturnStmt) s).getOp();
                    Value lhs = ((AssignStmt) toInline).getLeftOp();
                    AssignStmt as = Jimple.v().newAssignStmt(lhs, ro);
                    containerUnits.insertBefore(as, s);
                    containerUnits.swapWith(s, Jimple.v().newGotoStmt(exitPoint));
                } else if (s instanceof ReturnVoidStmt) {
                    containerUnits.swapWith(s, Jimple.v().newGotoStmt(exitPoint));
                }
            }
        }

        List<Unit> newStmts = new ArrayList<Unit>();
        for (Iterator<Unit> i
                = containerUnits.iterator(containerUnits.getSuccOf(toInline), containerUnits.getPredOf(exitPoint)); i.hasNext();) {
            newStmts.add(i.next());
        }

        // Remove the original statement toInline.
        containerUnits.remove(toInline);

        // Remove throw of inlinee method is used as throwable result, this will cause an error
        /*		if(exitPoint instanceof ThrowStmt) {
			ThrowStmt throwStmt = (ThrowStmt) (exitPoint);
			if(toInline instanceof AssignStmt) {
				Value lhs = ((AssignStmt) toInline).getLeftOp();
				if(throwStmt.getOp().equals(lhs))
					containerUnits.remove(exitPoint);
			}
		}*/
        // Resolve name collisions.
        LocalNameStandardizer.v().transform(containerB, "ji.lns");

        return newStmts;
    }
}
