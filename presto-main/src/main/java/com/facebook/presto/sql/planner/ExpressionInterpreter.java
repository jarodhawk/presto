package com.facebook.presto.sql.planner;

import com.facebook.presto.slice.Slice;
import com.facebook.presto.slice.Slices;
import com.facebook.presto.sql.compiler.Slot;
import com.facebook.presto.sql.compiler.SlotReference;
import com.facebook.presto.sql.tree.ArithmeticExpression;
import com.facebook.presto.sql.tree.AstVisitor;
import com.facebook.presto.sql.tree.CoalesceExpression;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.DoubleLiteral;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.IsNotNullPredicate;
import com.facebook.presto.sql.tree.IsNullPredicate;
import com.facebook.presto.sql.tree.LogicalBinaryExpression;
import com.facebook.presto.sql.tree.LongLiteral;
import com.facebook.presto.sql.tree.Node;
import com.facebook.presto.sql.tree.NotExpression;
import com.facebook.presto.sql.tree.NullIfExpression;
import com.facebook.presto.sql.tree.NullLiteral;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.facebook.presto.sql.tree.StringLiteral;
import com.facebook.presto.tuple.TupleReadable;

import java.util.Map;

import static com.google.common.base.Charsets.UTF_8;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.lang.String.format;

class ExpressionInterpreter
        extends AstVisitor<Object, TupleReadable[]>
{
    private final Map<Slot, Integer> slotToChannelMapping;

    public ExpressionInterpreter(Map<Slot, Integer> slotToChannelMapping)
    {
        this.slotToChannelMapping = slotToChannelMapping;
    }

    @Override
    public Object visitSlotReference(SlotReference node, TupleReadable[] inputs)
    {
        Slot slot = node.getSlot();
        Integer channel = slotToChannelMapping.get(slot);
        TupleReadable input = inputs[channel];

        // TODO: support channels with composite tuples
        if (input.isNull(0)) {
            return null;
        }

        switch (slot.getType()) {
            case LONG:
                return input.getLong(0);
            case DOUBLE:
                return input.getDouble(0);
            case STRING:
                return input.getSlice(0);
            default:
                throw new UnsupportedOperationException("not yet implemented: " + slot.getType());
        }
    }

    @Override
    protected Object visitQualifiedNameReference(QualifiedNameReference node, TupleReadable[] context)
    {
        throw new UnsupportedOperationException("QualifiedNames should be rewritten to slot references");
    }

    @Override
    protected Long visitLongLiteral(LongLiteral node, TupleReadable[] context)
    {
        return Long.valueOf(node.getValue());
    }

    @Override
    protected Double visitDoubleLiteral(DoubleLiteral node, TupleReadable[] context)
    {
        return Double.valueOf(node.getValue());
    }

    @Override
    protected Slice visitStringLiteral(StringLiteral node, TupleReadable[] context)
    {
        return Slices.wrappedBuffer(node.getValue().getBytes(UTF_8));
    }

    @Override
    protected Object visitNullLiteral(NullLiteral node, TupleReadable[] context)
    {
        return null;
    }

    @Override
    protected Object visitIsNullPredicate(IsNullPredicate node, TupleReadable[] context)
    {
        return process(node.getValue(), context) == null;
    }

    @Override
    protected Object visitIsNotNullPredicate(IsNotNullPredicate node, TupleReadable[] context)
    {
        return process(node.getValue(), context) != null;
    }

    @Override
    protected Object visitCoalesceExpression(CoalesceExpression node, TupleReadable[] context)
    {
        for (Expression expression : node.getOperands()) {
            Object value = process(expression, context);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Override
    protected Number visitArithmeticExpression(ArithmeticExpression node, TupleReadable[] context)
    {
        Number left = (Number) process(node.getLeft(), context);
        if (left == null) {
            return null;
        }
        Number right = (Number) process(node.getRight(), context);
        if (right == null) {
            return null;
        }

        switch (node.getType()) {
            case ADD:
                if (left instanceof Long && right instanceof Long) {
                    return left.longValue() + right.longValue();
                }
                else {
                    return left.doubleValue() + right.doubleValue();
                }
            case SUBTRACT:
                if (left instanceof Long && right instanceof Long) {
                    return left.longValue() - right.longValue();
                }
                else {
                    return left.doubleValue() - right.doubleValue();
                }
            case DIVIDE:
                if (left instanceof Long && right instanceof Long) {
                    return left.longValue() / right.longValue();
                }
                else {
                    return left.doubleValue() / right.doubleValue();
                }
            case MULTIPLY:
                if (left instanceof Long && right instanceof Long) {
                    return left.longValue() * right.longValue();
                }
                else {
                    return left.doubleValue() * right.doubleValue();
                }
            case MODULUS:
                if (left instanceof Long && right instanceof Long) {
                    return left.longValue() % right.longValue();
                }
                else {
                    return left.doubleValue() % right.doubleValue();
                }
            default:
                throw new UnsupportedOperationException("not yet implemented: " + node.getType());
        }
    }

    @Override
    protected Boolean visitComparisonExpression(ComparisonExpression node, TupleReadable[] context)
    {
        Object left = process(node.getLeft(), context);
        if (left == null) {
            return null;
        }
        Object right = process(node.getRight(), context);
        if (right == null) {
            return null;
        }

        if (left instanceof Number && right instanceof Number) {
            switch (node.getType()) {
                case EQUAL:
                    return ((Number) left).doubleValue() == ((Number) right).doubleValue();
                case NOT_EQUAL:
                    return ((Number) left).doubleValue() != ((Number) right).doubleValue();
                case LESS_THAN:
                    return ((Number) left).doubleValue() < ((Number) right).doubleValue();
                case LESS_THAN_OR_EQUAL:
                    return ((Number) left).doubleValue() <= ((Number) right).doubleValue();
                case GREATER_THAN:
                    return ((Number) left).doubleValue() > ((Number) right).doubleValue();
                case GREATER_THAN_OR_EQUAL:
                    return ((Number) left).doubleValue() >= ((Number) right).doubleValue();
            }
        }
        else if (left instanceof Slice && right instanceof Slice) {
            switch (node.getType()) {
                case EQUAL:
                    return left.equals(right);
                case NOT_EQUAL:
                    return !left.equals(right);
                case LESS_THAN:
                    return ((Slice) left).compareTo((Slice) right) < 0;
                case LESS_THAN_OR_EQUAL:
                    return ((Slice) left).compareTo((Slice) right) <= 0;
                case GREATER_THAN:
                    return ((Slice) left).compareTo((Slice) right) > 0;
                case GREATER_THAN_OR_EQUAL:
                    return ((Slice) left).compareTo((Slice) right) >= 0;
            }
        }

        throw new UnsupportedOperationException(format("not yet implemented: %s(%s, %s)", node.getType(), left.getClass().getName(), right.getClass().getName()));
    }

    @Override
    protected Object visitNullIfExpression(NullIfExpression node, TupleReadable[] context)
    {
        Object first = process(node.getFirst(), context);
        if (first == null) {
            return null;
        }
        Object second = process(node.getSecond(), context);
        if (second == null) {
            return null;
        }

        if (first instanceof Number && second instanceof Number) {
            return ((Number) first).doubleValue() == ((Number) second).doubleValue() ? null : first;
        }
        else if (first instanceof Slice && second instanceof Slice) {
            return first.equals(second) ? null : first;
        }
        throw new UnsupportedOperationException(format("not yet implemented: nullif(%s, %s)", first.getClass().getName(), second.getClass().getName()));
    }

    @Override
    protected Object visitNotExpression(NotExpression node, TupleReadable[] context)
    {
        Boolean value = (Boolean) process(node.getValue(), context);
        if (value == null) {
            return null;
        }
        return !value;
    }

    @Override
    protected Boolean visitLogicalBinaryExpression(LogicalBinaryExpression node, TupleReadable[] context)
    {
        switch (node.getType()) {
            case AND: {
                // if either left or right is false, result is always false regardless of nulls
                Boolean left = (Boolean) process(node.getLeft(), context);
                if (left == FALSE) {
                    return FALSE;
                }
                Boolean right = (Boolean) process(node.getRight(), context);
                if (right == FALSE) {
                    return FALSE;
                }
                // otherwise, if either is null, result is null
                if (left == null || right == null) {
                    return null;
                }
                return TRUE;
            }
            case OR: {
                // if either left or right is true, result is always true regardless of nulls
                Boolean left = (Boolean) process(node.getLeft(), context);
                if (left == TRUE) {
                    return TRUE;
                }
                Boolean right = (Boolean) process(node.getRight(), context);
                if (right == TRUE) {
                    return TRUE;
                }
                // otherwise, if either is null, result is null
                if (left == null || right == null) {
                    return null;
                }
                return false;
            }
        }

        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    protected Object visitExpression(Expression node, TupleReadable[] context)
    {
        throw new UnsupportedOperationException("not yet implemented: " + node.getClass().getName());
    }

    @Override
    protected Object visitNode(Node node, TupleReadable[] context)
    {
        throw new UnsupportedOperationException("Evaluator visitor can only handle Expression nodes");
    }
}