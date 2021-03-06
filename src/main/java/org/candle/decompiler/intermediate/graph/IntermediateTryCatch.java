package org.candle.decompiler.intermediate.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.bcel.generic.CodeExceptionGen;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.MethodGen;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.bcel.generic.InstructionHandle;
import org.candle.decompiler.intermediate.code.AbstractIntermediate;
import org.candle.decompiler.intermediate.code.BlockRange;
import org.candle.decompiler.intermediate.code.BlockRangeComparator;
import org.candle.decompiler.intermediate.code.CatchIntermediate;
import org.candle.decompiler.intermediate.code.FinallyIntermediate;
import org.candle.decompiler.intermediate.code.StatementIntermediate;
import org.candle.decompiler.intermediate.code.TryIntermediate;
import org.candle.decompiler.intermediate.expression.Declaration;
import org.candle.decompiler.intermediate.graph.context.IntermediateGraphContext;
import org.candle.decompiler.intermediate.graph.context.NullIntermediate;

public class IntermediateTryCatch {

	private static final Log LOG = LogFactory.getLog(IntermediateTryCatch.class);
	
	private final MethodGen method;
	private final IntermediateGraphContext igc;
	
	public IntermediateTryCatch(MethodGen method, IntermediateGraphContext igc) {
		this.method = method;
		this.igc = igc;
	}
	
	public void process() {
		
		Set<BlockRange> tryBlock = new TreeSet<BlockRange>(new BlockRangeComparator());
		Map<BlockRange, List<CodeExceptionGen>> tryRangeGen = new HashMap<BlockRange, List<CodeExceptionGen>>();
		Map<InstructionHandle, List<CodeExceptionGen>> tryRangeFinally = new  HashMap<InstructionHandle, List<CodeExceptionGen>>();
		
		
		//create try statements...
		for(CodeExceptionGen ceg : method.getExceptionHandlers()) {
			InstructionHandle min = ceg.getStartPC();
			InstructionHandle max = ceg.getEndPC();
			
			BlockRange tryRange = new BlockRange();
			tryRange.setStart(min);
			tryRange.setEnd(max);
			
			
			AbstractIntermediate handle = igc.findNextNode(ceg.getHandlerPC());
			LOG.debug("RANGE: "+ceg);
			LOG.debug("Range: "+tryRange+" , Target: "+handle.getInstruction().getPosition()+" , Handle: "+handle.getInstruction());
			
			
			if(ceg.getCatchType() == null) {
				if(!tryRangeFinally.containsKey(ceg.getHandlerPC())) {
					tryRangeFinally.put(ceg.getHandlerPC(), new LinkedList<CodeExceptionGen>());
				}
				tryRangeFinally.get(ceg.getHandlerPC()).add(ceg);
				continue;
			}
			
			tryBlock.add(tryRange);

			if(!tryRangeGen.containsKey(tryRange)) {
				tryRangeGen.put(tryRange, new LinkedList<CodeExceptionGen>());
			}				
			tryRangeGen.get(tryRange).add(ceg);
		}
		
		
		for(BlockRange tryRange : tryBlock) {
			//create try block... create each catch block... link the two together for graph sake.
			//look up block...
			InstructionHandle start = tryRange.getStart();
			TryIntermediate tryIntermediate = new TryIntermediate(start);
			tryIntermediate.getBlockRange().setStart(tryRange.getStart());
			tryIntermediate.getBlockRange().setEnd(tryRange.getEnd());
			
			
			igc.getGraph().addVertex(tryIntermediate);
			
			//add line between try and node.
			AbstractIntermediate tryFirst = igc.findNextNode(start);
			igc.redirectPredecessors(tryFirst, tryIntermediate);
			igc.getGraph().addEdge(tryIntermediate, tryFirst);
			
			
			if(tryRangeGen.containsKey(tryRange)) {
				//create catch statements...
				for(CodeExceptionGen ceg : tryRangeGen.get(tryRange)) {
					generateCatch(tryIntermediate, ceg);
				}
			}
		}
		
		
		//create a finally node for each handle of finally & link
		for(InstructionHandle finallyTargetHandle : tryRangeFinally.keySet()) {
			//get reference to target...
			AbstractIntermediate finallyTargetNode = igc.findNextNode(finallyTargetHandle);
			
			//change the instruction to a finally...
			FinallyIntermediate finallyIntermediate = new FinallyIntermediate(finallyTargetNode.getInstruction(), new HashSet<CodeExceptionGen>(tryRangeFinally.get(finallyTargetHandle)));
			igc.getGraph().addVertex(finallyIntermediate);
			
			//now, we need to redirect from the existing throws to finally.
			igc.redirectSuccessors(finallyTargetNode, finallyIntermediate);
			//retract existing.
			igc.getGraph().removeVertex(finallyTargetNode);
		}
		
		
	}

	private void generateCatch(TryIntermediate tryIntermediate, CodeExceptionGen ceg) {
		LOG.debug("CEG: "+ceg);
		
		
		//convert the node to catch blocks...
		AbstractIntermediate catchDeclaration = igc.getOrderedIntermediate().ceiling(new NullIntermediate(ceg.getHandlerPC()));
		
		LOG.debug("Catch Declaration:"+catchDeclaration);
		
		if(catchDeclaration instanceof StatementIntermediate) {
			StatementIntermediate declarationStatement = (StatementIntermediate)catchDeclaration;
			if(declarationStatement.getExpression() instanceof Declaration) {
				Declaration declaration = (Declaration)declarationStatement.getExpression();
				
				//now, we can convert this into a catch block.
				CatchIntermediate catchIntermediate = new CatchIntermediate(declarationStatement.getInstruction(), ceg, declaration.getVariable());
				igc.getGraph().addVertex(catchIntermediate);
				
				//redirect statement to catch.
				igc.redirectPredecessors(declarationStatement, catchIntermediate);
				igc.redirectSuccessors(declarationStatement, catchIntermediate);
				
				//now, we just need to remove the statement.
				igc.getGraph().removeVertex(declarationStatement);
				
				//populate the bounds..
				
				
				//add the link between try and catch.
				igc.getGraph().addEdge(tryIntermediate, catchIntermediate);
			}
			
		}
	}
	
	
	
}
