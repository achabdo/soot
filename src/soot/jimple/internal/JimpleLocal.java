/* Soot - a J*va Optimization Framework
 * Copyright (C) 1999 Patrick Lam
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


package soot.jimple.internal;

import soot.*;
import soot.jimple.*;
import soot.util.*;
import java.util.*;

public class JimpleLocal implements Local
{
    String name;
    Type type;

    int fixedHashCode;
    boolean isHashCodeChosen;

    int variableTableIndex = -1; // RoboVM note: Added in RoboVM.

    /** Constructs a JimpleLocal of the given name and type. */
    public JimpleLocal(String name, Type t)
    {
        this.name = name;
        this.type = t;
        Scene.v().getLocalNumberer().add( this );
    }

    /** Returns true if the given object is structurally equal to this one. */
    public boolean equivTo(Object o)
    {
        return this.equals( o );
    }

    /** Returns a hash code for this object, consistent with structural equality. */
    public int equivHashCode() 
    {
        return name.hashCode() * 101 + type.hashCode() * 17;
    }

    /** Returns a clone of the current JimpleLocal. */
    public Object clone()
    {
        // RoboVM note: Changed to also clone index.
        JimpleLocal l = new JimpleLocal(name, type);
        l.variableTableIndex = this.variableTableIndex;
        return l;
    }

    /** Returns the name of this object. */
    public String getName()
    {
        return name;
    }

    /** Sets the name of this object as given. */
    public void setName(String name)
    {
        this.name = name;
    }

    // RoboVM note: Start changes.
    public int getVariableTableIndex() {
        return variableTableIndex;
    }

    public void setVariableTableIndex(int variableTableIndex) {
        this.variableTableIndex = variableTableIndex;
    }

    private Set<Integer> sameSlotVariables;
    @Override
    public void setSameSlotVariables(Set<Integer> indexes) {
        this.sameSlotVariables = indexes;
    }

    @Override
    public Set<Integer> getSameSlotVariables() {
        return sameSlotVariables;
    }
    // RoboVM note: End changes.

    /** Returns a hashCode consistent with object equality. */
    public int hashCode()
    {
        if(!isHashCodeChosen)
        {
            // Set the hash code for this object
            
            if(name != null & type != null)
                fixedHashCode = name.hashCode() + 19 * type.hashCode();
            else if(name != null)
                fixedHashCode = name.hashCode();
            else if(type != null)
                fixedHashCode = type.hashCode();
            else
                fixedHashCode = 1;
                
            isHashCodeChosen = true;
        }
        
        return fixedHashCode;
    }
    
    /** Returns the type of this local. */
    public Type getType()
    {
        return type;
    }

    /** Sets the type of this local. */
    public void setType(Type t)
    {
        this.type = t;
    }

    public String toString()
    {
        return getName() + (variableTableIndex != -1 ? (", index=" + variableTableIndex) : "") +
                (sameSlotVariables != null ? ( ", vars=" + sameSlotVariables) : "");
    }
    
    public void toString(UnitPrinter up) {
        up.local(this);
    }

    public List getUseBoxes()
    {
        return AbstractUnit.emptyList;
    }

    public void apply(Switch sw)
    {
        ((JimpleValueSwitch) sw).caseLocal(this);
    }

    public final int getNumber() { return number; }
    public final void setNumber( int number ) { this.number = number; }

    private int number = 0;


}

