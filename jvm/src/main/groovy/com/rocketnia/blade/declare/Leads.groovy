// Leads.groovy
//
// Copyright 2010 Ross Angle
//
// This file is part of JVM-Blade.
//
// JVM-Blade is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published
// by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version.
//
// JVM-Blade is distributed in the hope that it will be useful, but
// WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with JVM-Blade.  If not, see <http://www.gnu.org/licenses/>.


package com.rocketnia.blade.declare

import com.rocketnia.blade.*


abstract class Lead extends RefMap {}

// A contribution of value to sig, expecting reducer to ultimately
// reduce the values. The next field is a nullary Blade function that
// will return a new Lead. Note that value can be a soft reference.
class LeadContrib extends Lead {
	Blade getSig() { get "sig" }
	Blade setSig( Blade val ) { set "sig", val }
	Blade getReducer() { get "reducer" }
	Blade setReducer( Blade val ) { set "reducer", val }
	Blade getValue() { get "value" }
	Blade setValue( Blade val ) { set "value", val }
	Blade getNext() { get "next" }
	Blade setNext( Blade val ) { set "next", val }
}

// A promise not to contribute to any sig that doesn't satisfy the
// filter. The next field is a nullary Blade function that will return
// a new Lead.
class LeadPromise extends Lead {
	Blade getFilter() { get "filter" }
	Blade setFilter( Blade val ) { set "filter", val }
	Blade getNext() { get "next" }
	Blade setNext( Blade val ) { set "next", val }
}

// The result of a lead that has errored out. Note that this has no
// next continuation.
class LeadErr extends Lead {
	Blade getError() { get "error" }
	Blade setError( Blade val ) { set "error", val }
}

// The result of a lead that has run its course. Note that this has no
// next continuation.
class LeadEnd extends Lead {}

// A lead which will continue according to whatever Lead object is
// returned from a Blade calculation.
class LeadCalc extends Lead {
	Blade getCalc() { get "calc" }
	Blade setCalc( Blade val ) { set "calc", val }
}

// A lead which will continue as two separate leads.
class LeadSplit extends Lead {
	Blade getFirst() { get "first" }
	Blade setFirst( Blade val ) { set "first", val }
	Blade getSecond() { get "second" }
	Blade setSecond( Blade val ) { set "second", val }
}


final class Leads
{
	private Leads() {}
	
	// This returns a two-element list containing a Lead and a boolean
	// indicating whether any advancement actually happened. The Lead
	// will be either a LeadEnd, a LeadSplit, a LeadContrib, or a
	// LeadCalc whose inner Calc is a valid result for
	// { a, b, c -> Calcs.advanceCalcRepeatedly( a, b, c )[ 0 ] }.
	// However, it will only be a LeadContrib if none of the lead's
	// promises reject the sig and at least one of them requires an
	// unsatisfied hard ask.
	//
	// The addContrib parameter should be a function with side effects
	// that takes a sig, a reducer, and a contributed value. It
	// shouldn't test the contribution against the lead's promises;
	// this takes care of that step already. The return value of
	// addContrib should usually be null, but in case the contribution
	// is obstructed by a hard ask when comparing reducers, it should
	// return the ref which is asked for.
	//
	// The bladeTruthy parameter should be a closure that accepts a
	// Blade value and returns either true, false, or a hard-asked-for
	// ref.
	//
	static List advanceLeadRepeatedly( Lead lead, Closure calcCall,
		Closure getRef, Closure addContrib, Closure addPromise,
		Closure getPromises, Closure bladeTruthy )
	{
		def refIsSet = { Refs.isSetDirect getRef( it ) }
		
		def harden = { [
			new LeadCalc( calc: new CalcHardAsk(
				ref: it.ref, next: BuiltIn.of { calc } ) ),
			true
		] }
		
		for ( boolean didAnything = false; ; didAnything = true )
		{
			switch ( lead )
			{
			case LeadEnd:
			case LeadSplit:
				return [ lead, didAnything ]
				
			case LeadErr:
				def error = ((LeadErr)lead).error
				if ( error in Ref )
					return harden( error )
				
				throw new RuntimeException(
					"A lead resulted in this error: $error" )
				
			case LeadContrib:
				def lead2 = (LeadContrib)lead
				
				def sig = lead2.sig
				
				boolean anyAsks = false
				for ( filter in getPromises() )
				{
					def ( Calc advanced, did ) =
						Calcs.advanceCalcRepeatedly(
							calcCall( filter, [ sig ] ),
							calcCall,
							getRef
						)
					
					if ( advanced in CalcHardAsk )
						anyAsks = true
					else
					{
						def truth = bladeTruthy(
							((CalcResult)advanced).value )
						
						// TODO: See if this would be better as a
						// LeadErr instead.
						if ( truth == false )
						throw new RuntimeException(
							   "A lead broke a promise not to"
							+ " contribute to this sig: $lead2.sig" )
						else if ( truth != true )
							anyAsks = true
					}
				}
				
				if ( anyAsks )
					return [ lead, didAnything ]
				
				
				def neededRef = Refs.anyNeededRef( sig )
				if ( !null.is( neededRef ) )
					return harden( sig: neededRef )
				
				def reducer = lead2.reducer
				neededRef = Refs.anyNeededRef( reducer )
				if ( !null.is( neededRef ) )
					return harden( ref: neededRef )
				
				neededRef = addContrib( sig, reducer, lead2.value )
				if ( !null.is( neededRef ) )
					return harden( ref: neededRef )
				
				lead =
					new LeadCalc( calc: calcCall( lead2.next, [] ) )
				break
				
			case LeadPromise:
				def lead2 = (LeadPromise)lead
				addPromise lead2.filter
				lead = calcCall( lead2.next, [] )
				break
				
			case LeadCalc:
				def initialInnerCalc = ((LeadCalc)lead).calc
				switch ( initialInnerCalc )
				{
				case Ref: return harden( initialInnerCalc )
					
				case CalcResult:
					def value = ((CalcResult)initialInnerCalc).value
					if ( value in Ref )
						return harden( value )
					
					// TODO: See if this would be better as a LeadErr
					// instead.
					if ( !(value in Lead) )
						throw new RuntimeException(
							   "A LeadCalc's inner result wasn't a"
							+ " Lead." )
					
					lead = value
					break
					
				default:
					// TODO: Figure out the best way to treat inner
					// errors with respect to their outer
					// calculations.
					def ( finalInnerCalc, innerDid ) =
						Calcs.advanceCalcRepeatedly(
							initialInnerCalc, calcCall, getRef )
					
					if ( !innerDid )
						return [ lead, didAnything ]
					
					lead = new LeadCalc( calc: finalInnerCalc )
					break
				}
				break
				
			default: throw new RuntimeException(
				"An unknown Lead type was encountered." )
			}
		}
	}
}
