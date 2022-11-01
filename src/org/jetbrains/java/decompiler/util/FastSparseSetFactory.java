// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.util;

import java.util.*;

public class FastSparseSetFactory<E> {

  private final PackedMap<E> colValuesInternal = new PackedMap<>();

  private int lastBlock;

  private int lastMask;

  public FastSparseSetFactory(Collection<? extends E> set) {

    int block = -1;
    int mask = -1;
    int index = 0;

    for (E element : set) {

      block = index / 32;

      if (index % 32 == 0) {
        mask = 1;
      }
      else {
        mask <<= 1;
      }

      colValuesInternal.putWithKey(PackedMap.pack(mask, block), element);

      index++;
    }

    lastBlock = block;
    lastMask = mask;
  }

  private long addElement(E element) {

    if (lastMask == -1 || lastMask == 0x80000000) {
      lastMask = 1;
      lastBlock++;
    }
    else {
      lastMask <<= 1;
    }

    long pointer = PackedMap.pack(lastMask, lastBlock);
    colValuesInternal.putWithKey(pointer, element);

    return pointer;
  }

  public FastSparseSet<E> createEmptySet() {
    return new FastSparseSet<>(this);
  }

  private int getLastBlock() {
    return lastBlock;
  }

  private PackedMap<E> getInternalValuesCollection() {
    return colValuesInternal;
  }


  public static final class FastSparseSet<E> implements Iterable<E> {
    public static final FastSparseSet[] EMPTY_ARRAY = new FastSparseSet[0];

    private final FastSparseSetFactory<E> factory;

    private final PackedMap<E> colValuesInternal;

    private int[] data;
    private int[] next;

    private FastSparseSet(FastSparseSetFactory<E> factory) {
      this.factory = factory;
      this.colValuesInternal = factory.getInternalValuesCollection();

      // Originally, this returned factory.getLastBlock() + 1. However, in the most common case, only 1 element is added.
      // This means that the array is unnecessarily large. Instead, max(lastBlock, 1) is used to ensure empty factories
      // don't produce -1 lengths.
      // TODO: the array init of size 1 can be elided, and the array can be lazy initialized when sized above 1
      int length = Math.max(factory.getLastBlock(), 1);
      this.data = new int[length];
      this.next = null;
    }

    private FastSparseSet(FastSparseSetFactory<E> factory, int[] data, int[] next) {
      this.factory = factory;
      this.colValuesInternal = factory.getInternalValuesCollection();

      this.data = data;
      this.next = next;
    }

    public FastSparseSet<E> getCopy() {
      int[] newData = new int[this.data.length];
      System.arraycopy(this.data, 0, newData, 0, newData.length);
      int[] newNext = null;

      if (this.next != null) {
        newNext = new int[this.next.length];
        System.arraycopy(this.next, 0, newNext, 0, newNext.length);
      }

      return new FastSparseSet<>(factory, newData, newNext);
    }

    private int[] ensureCapacity(int index) {

      int newlength = data.length;
      if (newlength == 0) {
        newlength = 1;
      }

      while (newlength <= index) {
        newlength *= 2;
      }

      data = Arrays.copyOf(data, newlength);
      if (next != null) {
        next = Arrays.copyOf(next, newlength);
      }

      return data;
    }

    public void add(E element) {
      long index;
      if (!colValuesInternal.containsKey(element)) {
        index = factory.addElement(element);
      } else {
        index = colValuesInternal.getWithKey(element);
      }

      int block = PackedMap.unpackLow(index);
      if (block >= data.length) {
        ensureCapacity(block);
      }

      data[block] |= PackedMap.unpackHigh(index);

      changeNext(block, getNextIdx(next, block), block);
    }

    private static int getNextIdx(int[] next, int block) {
      return next == null ? 0 : next[block];
    }

    private int[] allocNext() {
      if (next == null) {
        next = new int[data.length];
      }

      return next;
    }

    public void remove(E element) {
      long index;
      if (!colValuesInternal.containsKey(element)) {
        index = factory.addElement(element);
      } else {
        index = colValuesInternal.getWithKey(element);
      }

      int block = PackedMap.unpackLow(index);
      if (block < data.length) {
        data[block] &= ~PackedMap.unpackHigh(index);

        if (data[block] == 0) {
          changeNext(block, block, getNextIdx(next, block));
        }
      }
    }

    public boolean contains(E element) {
      long index;
      if (!colValuesInternal.containsKey(element)) {
        index = factory.addElement(element);
      } else {
        index = colValuesInternal.getWithKey(element);
      }

      int block = PackedMap.unpackLow(index);
      return block < data.length && ((data[block] & PackedMap.unpackHigh(index)) != 0);
    }

    private void setNext() {

      int link = 0;
      for (int i = data.length - 1; i >= 0; i--) {
        if (link != 0 && next == null) {
          allocNext();
          next[i] = link;
        }

        if (data[i] != 0) {
          link = i;
        }
      }
    }

    private void changeNext(int key, int oldnext, int newnext) {
      for (int i = key - 1; i >= 0; i--) {
        if (getNextIdx(next, i) == oldnext) {
          allocNext();
          next[i] = newnext;
        } else {
          break;
        }
      }
    }

    public void union(FastSparseSet<E> set) {

      int[] extdata = set.getData();
      int[] extnext = set.getNext();
      int[] intdata = data;
      int intlength = intdata.length;

      int pointer = 0;
      do {
        if (pointer >= intlength) {
          intdata = ensureCapacity(extdata.length - 1);
        }

        boolean nextrec = (intdata[pointer] == 0);
        intdata[pointer] |= extdata[pointer];

        if (nextrec) {
          changeNext(pointer, getNextIdx(next, pointer), pointer);
        }

        pointer = getNextIdx(extnext, pointer);
      }
      while (pointer != 0);
    }

    public void intersection(FastSparseSet<E> set) {
      int[] extdata = set.getData();
      int[] intdata = data;

      int minlength = Math.min(extdata.length, intdata.length);

      for (int i = minlength - 1; i >= 0; i--) {
        intdata[i] &= extdata[i];
      }

      for (int i = intdata.length - 1; i >= minlength; i--) {
        intdata[i] = 0;
      }

      setNext();
    }

    public void complement(FastSparseSet<E> set) {

      int[] extdata = set.getData();
      int[] intdata = data;
      int extlength = extdata.length;

      int pointer = 0;
      do {
        if (pointer >= extlength) {
          break;
        }

        intdata[pointer] &= ~extdata[pointer];
        if (intdata[pointer] == 0) {
          changeNext(pointer, pointer, getNextIdx(next, pointer));
        }

        pointer = getNextIdx(next, pointer);
      }
      while (pointer != 0);
    }

    @Override
    public int hashCode() {
      return toPlainSet().hashCode();
    }

    public boolean equals(Object o) {
      if (o == this) return true;
      if (!(o instanceof FastSparseSet)) return false;

      int[] longdata = ((FastSparseSet)o).getData();
      int[] shortdata = data;

      if (data.length > longdata.length) {
        shortdata = longdata;
        longdata = data;
      }

      for (int i = shortdata.length - 1; i >= 0; i--) {
        if (shortdata[i] != longdata[i]) {
          return false;
        }
      }

      for (int i = longdata.length - 1; i >= shortdata.length; i--) {
        if (longdata[i] != 0) {
          return false;
        }
      }

      return true;
    }

    public int getCardinality() {

      boolean found = false;
      int[] intdata = data;

      for (int i = intdata.length - 1; i >= 0; i--) {
        int block = intdata[i];
        if (block != 0) {
          if (found) {
            return 2;
          }
          else {
            if ((block & (block - 1)) == 0) {
              found = true;
            }
            else {
              return 2;
            }
          }
        }
      }

      return found ? 1 : 0;
    }

    public boolean isEmpty() {
      return data.length == 0 || (getNextIdx(next, 0) == 0 && data[0] == 0);
    }

    @Override
    public Iterator<E> iterator() {
      return new FastSparseSetIterator<>(this);
    }

    public Set<E> toPlainSet() {
      HashSet<E> set = new HashSet<>();

      int[] intdata = data;

      int size = data.length * 32;
      if (size > colValuesInternal.size()) {
        size = colValuesInternal.size();
      }

      for (int i = size - 1; i >= 0; i--) {
        long index = colValuesInternal.get(i);

        if ((intdata[PackedMap.unpackLow(index)] & PackedMap.unpackHigh(index)) != 0) {
          set.add(colValuesInternal.getKey(i));
        }
      }

      return set;
    }

    public String toString() {
      return toPlainSet().toString();
    }

    private int[] getData() {
      return data;
    }

    private int[] getNext() {
      return next;
    }

    private FastSparseSetFactory<E> getFactory() {
      return factory;
    }
  }

  public static final class FastSparseSetIterator<E> implements Iterator<E> {

    private final PackedMap<E> colValuesInternal;
    private final int[] data;
    private final int[] next;
    private final int size;

    private int pointer = -1;
    private int next_pointer = -1;

    private FastSparseSetIterator(FastSparseSet<E> set) {
      colValuesInternal = set.getFactory().getInternalValuesCollection();
      data = set.getData();
      next = set.getNext();
      size = colValuesInternal.size();
    }

    private int getNextIndex(int index) {

      index++;
      int bindex = index >>> 5;
      int dindex = index & 0x1F;

      while (bindex < data.length) {
        int block = data[bindex];

        if (block != 0) {
          block >>>= dindex;
          while (dindex < 32) {
            if ((block & 1) != 0) {
              return (bindex << 5) + dindex;
            }
            block >>>= 1;
            dindex++;
          }
        }

        dindex = 0;
        bindex = next == null ? 0 : next[bindex];

        if (bindex == 0) {
          break;
        }
      }

      return -1;
    }

    @Override
    public boolean hasNext() {
      next_pointer = getNextIndex(pointer);
      return (next_pointer >= 0);
    }

    @Override
    public E next() {
      if (next_pointer >= 0) {
        pointer = next_pointer;
      }
      else {
        pointer = getNextIndex(pointer);
        if (pointer == -1) {
          pointer = size;
        }
      }

      next_pointer = -1;
      return pointer < size ? colValuesInternal.getKey(pointer) : null;
    }

    @Override
    public void remove() {
      long index = colValuesInternal.get(pointer);
      data[PackedMap.unpackLow(index)] &= ~PackedMap.unpackHigh(index);
    }
  }
}

