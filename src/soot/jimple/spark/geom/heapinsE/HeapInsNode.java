/* Soot - a J*va Optimization Framework
 * Copyright (C) 2011 Richard Xiao
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
package soot.jimple.spark.geom.heapinsE;
//import gnu.trove.THashSet;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;

import soot.Type;
import soot.jimple.spark.geom.geomPA.CallsiteContextVar;
import soot.jimple.spark.geom.geomPA.GeomPointsTo;
import soot.jimple.spark.geom.geomPA.IVarAbstraction;
import soot.jimple.spark.geom.geomPA.IWorklist;
import soot.jimple.spark.geom.geomPA.PlainConstraint;
import soot.jimple.spark.geom.geomPA.SegmentNode;
import soot.jimple.spark.geom.geomPA.ZArrayNumberer;
import soot.jimple.spark.pag.AllocNode;
import soot.jimple.spark.pag.LocalVarNode;
import soot.jimple.spark.pag.Node;
import soot.jimple.spark.pag.StringConstantNode;

/**
 * Constraints graph node, each node represents a variable
 * @author richardxx
 *
 */
public class HeapInsNode extends IVarAbstraction
{
	// The targets of directed edges on the constraint graph
	public HashMap<HeapInsNode, HeapInsIntervalManager> flowto;

	// The objects this variable points to
	public HashMap<AllocNode, HeapInsIntervalManager> pt_objs;
	
	// Newly added points-to tuple
	public Map<AllocNode, HeapInsIntervalManager> new_pts;
	
	// store/load complex constraints
	public Vector<PlainConstraint> complex_cons = null;

	// The lower two bytes store the budget for flow edges, the higher bytes are for points-to facts
	public int compact_budget_rep;
	
	public HeapInsNode( Node thisVar ) 
	{
		me = thisVar;
		flowto = new HashMap<HeapInsNode, HeapInsIntervalManager>();
		pt_objs = new HashMap<AllocNode, HeapInsIntervalManager>();
		new_pts = new HashMap<AllocNode, HeapInsIntervalManager>();
		
		// We classify the nodes that can hold the max number of contexts shapes
		int size = me.getP2Set().size();
		if ( size <= 10 )
			compact_budget_rep = GeomPointsTo.max_pts_budget / 3;
		else if ( size <= 50 )
			compact_budget_rep = GeomPointsTo.max_pts_budget * 2 / 3;
		else
			compact_budget_rep = GeomPointsTo.max_cons_budget;
	}
	
	@Override
	public void reconstruct() 
	{
		new_pts = new HashMap<AllocNode, HeapInsIntervalManager>();
	
		if ( complex_cons != null )
			complex_cons.clear();
		
		if ( flowto != null ) {
			for ( HeapInsIntervalManager him : flowto.values() ) {
				him.clear();
			}
		}
		
		if ( pt_objs != null ) {
			for ( HeapInsIntervalManager him : pt_objs.values() ) {
				him.clear();
			}
		}
	}

	@Override
	public void do_before_propagation()
	{
		if ( complex_cons == null )
			do_pts_interval_merge();
		
		if ( !(me instanceof LocalVarNode) )
			do_flow_edge_interval_merge();
	}
	
	/**
	 * Remember to clean the is_new flag
	 */
	@Override
	public void do_after_propagation() 
	{
		for (HeapInsIntervalManager im : new_pts.values() ) {
			im.flush();
		}
		
		new_pts = new HashMap<AllocNode, HeapInsIntervalManager>();
	}

	@Override
	public boolean is_empty() {
		return pt_objs.size() == 0;
	}

	@Override
	public boolean has_new_pts() {
		return new_pts.size() != 0;
	}

	@Override
	public int num_of_diff_objs() {
		return pt_objs.size();
	}

	@Override
	public int num_of_diff_edges() {
		return flowto.size();
	}
	
	@Override
	public boolean add_points_to_3(AllocNode obj, long I1, long I2, long L) 
	{	
		HeapInsIntervalManager im = pt_objs.get(obj);
		
		if ( im == null ) {
			im = new HeapInsIntervalManager();
			pt_objs.put(obj, im);
		}
		
		SegmentNode p = im.add_new_interval(I1, I2, L);
		if ( p != null ) {
			new_pts.put(obj, im);
			return true;
		}
		
		return false;
	}
	
	@Override
	public boolean add_points_to_4(AllocNode obj, long I1, long I2,
			long L1, long L2) 
	{
		return false;
	}
	
	@Override
	public boolean add_simple_constraint_3(IVarAbstraction qv, long I1, long I2, long L) 
	{
		HeapInsIntervalManager im = flowto.get(qv);
		
		if ( im == null ) {
			im = new HeapInsIntervalManager();
			flowto.put( (HeapInsNode)qv, im);
		}
		
		return im.add_new_interval(I1, I2, L) != null;
	}

	@Override
	public boolean add_simple_constraint_4(IVarAbstraction qv, long I1, long I2,
			long L1, long L2) 
	{
		return false;
	}
	
	@Override
	public void put_complex_constraint(PlainConstraint cons) 
	{
		if ( complex_cons == null )
			complex_cons = new Vector<PlainConstraint>();
		    complex_cons.add( cons );
	}

	/**
	 *  Discard all context sensitive tuples which are covered by insensitive ones
	 */
	@Override
	public void drop_duplicates()
	{
		for ( Iterator<AllocNode> it = pt_objs.keySet().iterator(); it.hasNext(); ) {
			HeapInsIntervalManager im = pt_objs.get( it.next() );
			im.remove_useless_intervals();
		}
	}
	
	/**
	 * An efficient implementation of differential propagation.
	 */
	@Override
	public void propagate(GeomPointsTo ptAnalyzer, IWorklist worklist) 
	{
		int i, j;
		AllocNode obj;
		SegmentNode pts, pe, int_entry1[], int_entry2[];
		HeapInsIntervalManager him;
		HeapInsNode qn, objn;
		boolean added, has_new_edges;
		
		// We first build the new flow edges via the field dereferences
		if ( complex_cons != null ) {
			for ( Map.Entry<AllocNode, HeapInsIntervalManager> entry : new_pts.entrySet() ) {
				obj = entry.getKey();
				int_entry1 = entry.getValue().get_intervals();
				
				for (PlainConstraint pcons : complex_cons) {
					// Construct the two variables in assignment
					objn = (HeapInsNode)ptAnalyzer.findAndInsertInstanceField(obj, pcons.f);
					qn = (HeapInsNode) pcons.otherSide;
					
					for ( i = 0; i < HeapInsIntervalManager.Divisions; ++i ) {
						pts = int_entry1[i];
						while ( pts != null && pts.is_new ) {
							switch ( pcons.type ) {
							case GeomPointsTo.STORE_CONS:
								// Store, qv -> pv.field
								// pts.I2 may be zero, pts.L may be less than zero
								if ( qn.add_simple_constraint_3( objn,
										pcons.code == GeomPointsTo.ONE_TO_ONE ? pts.I1 : 0,
										pts.I2,
										pts.L < 0  ? -pts.L : pts.L
								) )
									worklist.push( qn );
								break;
								
							case GeomPointsTo.LOAD_CONS:
								// Load, pv.field -> qv
								if ( objn.add_simple_constraint_3( qn, 
										pts.I2, 
										pcons.code == GeomPointsTo.ONE_TO_ONE ? pts.I1 : 0,
										pts.L < 0 ? -pts.L : pts.L
								) )
									worklist.push( objn );
								break;
								
							default:
								throw new RuntimeException("Wrong Complex Constraint");
							}
							
							pts = pts.next;
						}
					}
				}
			}
		}
		
		for ( Map.Entry<HeapInsNode, HeapInsIntervalManager> entry1 : flowto.entrySet() ) {
			// Second get the flow-to intervals
			added = false;
			qn = entry1.getKey();
			him = entry1.getValue();
			int_entry2 = him.get_intervals();
			has_new_edges = him.isThereUnprocessedObject();
			Map<AllocNode, HeapInsIntervalManager> objs = ( has_new_edges ? pt_objs : new_pts );
			
			
			for (Map.Entry<AllocNode, HeapInsIntervalManager> entry2 : objs.entrySet()) {
				// First get the points-to intervals
				obj = entry2.getKey();
				if (!ptAnalyzer.castNeverFails(obj.getType(), qn.getWrappedNode().getType()))
					continue;
				
				int_entry1 = entry2.getValue().get_intervals();
				
				// We pair up all the interval points-to tuples and interval flow edges
				for (i = 0; i < HeapInsIntervalManager.Divisions; ++i) {
					pts = int_entry1[i];
					while ( pts != null ) {
						if (  !has_new_edges && !pts.is_new )
							break;
						
						for ( j = 0; j < HeapInsIntervalManager.Divisions; ++j) {
							pe = int_entry2[j];
							while ( pe != null ) {
								if ( pts.is_new || pe.is_new ) {
									// Propagate this object
									if ( add_new_points_to_tuple(pts, pe, obj, qn) )
										added = true;
								}
								else
									break;
								
								pe = pe.next;
							}
						}
						
						pts = pts.next;
					}
				}
			}
			
			if ( added )
				worklist.push( qn );
			
			// Now, we clean the new edges if necessary
			if ( has_new_edges ) {
				him.flush();
			}
		}
	}
	
	@Override
	public int count_pts_intervals(AllocNode obj) 
	{
		int ret = 0;
		SegmentNode[] int_entry = find_points_to(obj);
		
		for (int j = 0; j < HeapInsIntervalManager.Divisions; ++j) {
			SegmentNode p = int_entry[j];
			while (p != null) {
				++ret;
				p = p.next;
			}
		}
		
		return ret;
	}

	@Override
	public int count_flow_intervals(IVarAbstraction qv) 
	{
		int ret = 0;
		SegmentNode[] int_entry = find_flowto( (HeapInsNode)qv );
		
		for (int j = 0; j < HeapInsIntervalManager.Divisions; ++j) {
			SegmentNode p = int_entry[j];
			while (p != null) {
				++ret;
				p = p.next;
			}
		}
		
		return ret;
	}

	/**
	 * Query if this pointer and qv could point to the same object under any contexts
	 */
	@Override
	public boolean heap_sensitive_intersection(IVarAbstraction qv) 
	{
		int i, j;
		HeapInsNode qn;
		SegmentNode p, q, pt[], qt[];
		
		qn = (HeapInsNode)qv;
		
		for (Iterator<AllocNode> it = pt_objs.keySet().iterator(); it.hasNext();) {
			AllocNode an = it.next();
			if ( an instanceof StringConstantNode ) continue;
			qt = qn.find_points_to(an);
			if (qt == null) continue;
			pt = find_points_to(an);

			for (i = 0; i < HeapInsIntervalManager.Divisions; ++i) {
				p = pt[i];
				while (p != null) {
					for (j = 0; j < HeapInsIntervalManager.Divisions; ++j) {
						q = qt[j];
						while (q != null) {
							if (quick_intersecting_test(p, q))
								return true;
							q = q.next;
						}
					}
					p = p.next;
				}
			}
		}

		return false;
	}
	
	@Override
	public boolean pointer_sensitive_points_to(long context, AllocNode obj) 
	{
		SegmentNode[] int_entry = find_points_to(obj);
		if ( int_entry[0] != null )
			return true;
		
		for ( int i = 1; i < HeapInsIntervalManager.Divisions; ++i ) {
			SegmentNode p = int_entry[i];
			while ( p != null ) {
				if ( p.I1 <= context && (p.I1 + p.L) > context )
					return true;
				p = p.next;
			}
		}
		
		return false;
	}
	
	@Override
	public boolean test_points_to_has_types(Set<Type> types) 
	{
		for (Iterator<AllocNode> it = pt_objs.keySet().iterator(); it.hasNext();) {
			AllocNode an = it.next();
			if (types.contains( an.getType() ) ) {
				return true;
			}
		}
		
		return false;
	}

	@Override
	public Set<AllocNode> get_all_points_to_objects() 
	{
		return pt_objs.keySet();
	}

	@Override
	public void print_context_sensitive_points_to( PrintStream outPrintStream ) 
	{
		for (Iterator<AllocNode> it = pt_objs.keySet().iterator(); it.hasNext();) {
			AllocNode obj = it.next();
			SegmentNode[] int_entry = find_points_to( obj );
			for (int j = 0; j < HeapInsIntervalManager.Divisions; ++j) {
				SegmentNode p = int_entry[j];
				while (p != null) {
					outPrintStream.println("(" + obj.toString() + ", " + p.I1 + ", "
							+ p.I2 + ", " + p.L + ")");
					p = p.next;
				}
			}
		}
	}
	
	//---------------------------------Private Functions----------------------------------------
	private SegmentNode[] find_flowto(HeapInsNode qv) {
		HeapInsIntervalManager im = flowto.get(qv);
		if ( im == null ) return null;
		return im.get_intervals();
	}

	private SegmentNode[] find_points_to(AllocNode obj) {
		HeapInsIntervalManager im = pt_objs.get(obj);
		if ( im == null ) return null;
		return im.get_intervals();
	}
	
	/**
	 *  Merge the context sensitive tuples, and make a single insensitive tuple
	 */
	private void do_pts_interval_merge()
	{
		for ( HeapInsIntervalManager him : new_pts.values() ) {
			him.merge_points_to_tuples( compact_budget_rep );
		}
	}
	
	private void do_flow_edge_interval_merge()
	{
		for ( HeapInsIntervalManager him : flowto.values() ) {
			if ( him.isThereUnprocessedObject() )
				him.merge_flow_edges();
		}
	}
	
	// Implement the inferences
	private boolean add_new_points_to_tuple( SegmentNode pts, SegmentNode pe, 
			AllocNode obj, HeapInsNode qn )
	{
		long interI, interJ, L, eL;
		long Iqv, Io;
		
		L = ( pts.L < 0 ? -pts.L : pts.L );
		eL = ( pe.L < 0 ? -pe.L : pe.L );
		
		// Special Cases
		if (pts.I1 == 0 || pe.I1 == 0) {
			// Make it pointer sensitive but heap insensitive
			if ( pe.I2 != 0 ) {
				// Support limited type of many-many relationship
				if ( pts.I2 != 0 && eL == L )
					return qn.add_points_to_3( obj, 
							pe.I2, pts.I2, L == 1 ? 1 : -L );
				
				return qn.add_points_to_3( obj,
						pe.I2, 0, eL);
			}
			
			return qn.add_points_to_3( obj,
					0, pts.I2, L);
		}
		
		// The left-end is the larger one
		interI = pe.I1 < pts.I1 ? pts.I1 : pe.I1;
		// The right-end is the smaller one
		interJ = (pe.I1 + eL < pts.I1 + L ? pe.I1 + eL : pts.I1 + L);
		
		if (interI < interJ) {
			// The intersection is non-empty
			Iqv = (pe.I2 == 0 ? 0 : interI - pe.I1 + pe.I2);
			Io = (pts.I2 == 0 ? 0 : interI - pts.I1 + pts.I2);
			if ( Iqv != 0 && Io != 0 && (pts.L < 0 || pe.L < 0) )
				L = interI - interJ;
			else
				L = interJ - interI;
			
			return qn.add_points_to_3( obj,
						Iqv, Io, L );
		}
		
		return false;
	}
	
	// We only test if their points-to objects intersected under context
	// insensitive manner
	private boolean quick_intersecting_test(SegmentNode p, SegmentNode q) {
		if ( p.I2 == 0 || q.I2 == 0 )
			return true;
		
		if (p.I2 >= q.I2)
			return p.I2 < q.I2 + (q.L < 0 ? -q.L : q.L);
		return q.I2 < p.I2 + (p.L < 0 ? -p.L : p.L);
	}
	
	public void add_shapes_to_set(TreeSet<SegmentNode> ts[]) 
	{
		SegmentNode int_entry[], p;
		
		for (Iterator<AllocNode> it_pts = pt_objs.keySet().iterator(); it_pts.hasNext();) {
			int_entry = find_points_to( it_pts.next() );
			for ( int i = 0; i < HeapInsIntervalManager.Divisions; ++i ) {
				p = int_entry[i];
				while ( p != null ) {
					ts[0].add( p );
					p = p.next;
				}
			}
		}
		
		for (Iterator<HeapInsNode> it_flow = flowto.keySet().iterator(); it_flow.hasNext();) {
			int_entry = find_flowto( it_flow.next() );
			for ( int i = 0; i < HeapInsIntervalManager.Divisions; ++i ) {
				p = int_entry[i];
				while ( p != null ) {
					ts[0].add( p );
					p = p.next;
				}
			}
		}
	}

	@Override
	public boolean pointer_interval_points_to(long l, long r, AllocNode obj) 
	{
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void remove_points_to(AllocNode obj) 
	{
		// TODO Auto-generated method stub
		
	}

	@Override
	public void discard() {
		flowto = null;
		pt_objs = null;
		new_pts = null;
		complex_cons = null;
	}

	@Override
	public int count_new_pts_intervals() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int get_all_context_sensitive_objects(long l, long r,
			ZArrayNumberer<CallsiteContextVar> all_objs,
			Vector<CallsiteContextVar> outList) {
		// TODO Auto-generated method stub
		return 0;
	}
}
