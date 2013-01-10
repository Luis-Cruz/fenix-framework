package pt.ist.fenixframework.core.adt.skiplist;

import java.io.Serializable;

import pt.ist.fenixframework.core.AbstractDomainObject;

public class ForwardArrayShadow implements Serializable {

    private static final long serialVersionUID = 5348029777012836627L;
    
    public SkipListNodeShadow[] forward;
    
    private ForwardArrayShadow() {
	
    }
    
    public ForwardArrayShadow(int level) {
	this.forward = new SkipListNodeShadow[level + 1];
    }
    
    public ForwardArrayShadow(SkipListNodeShadow[] forward) {
	this.forward = forward;
    }
    
}
