/*******************************************************************************
 * Copyright (c) 2017-2018 Aion foundation.
 *
 *     This file is part of the aion network project.
 *
 *     The aion network project is free software: you can redistribute it
 *     and/or modify it under the terms of the GNU General Public License
 *     as published by the Free Software Foundation, either version 3 of
 *     the License, or any later version.
 *
 *     The aion network project is distributed in the hope that it will
 *     be useful, but WITHOUT ANY WARRANTY; without even the implied
 *     warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *     See the GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with the aion network project source files.
 *     If not, see <https://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Aion foundation.
 *     
 ******************************************************************************/

package org.aion.zero.impl.db;

import org.aion.base.db.IByteArrayKeyValueDatabase;
import org.aion.base.util.ByteUtil;
import org.aion.mcf.db.AbstractPowBlockstore;
import org.aion.mcf.ds.DataSourceArray;
import org.aion.mcf.ds.ObjectDataSource;
import org.aion.mcf.ds.Serializer;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.rlp.RLP;
import org.aion.rlp.RLPList;
import org.aion.zero.impl.types.AionBlock;
import org.aion.zero.types.A0BlockHeader;
import org.aion.zero.types.IAionBlock;
import org.slf4j.Logger;

import java.io.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.math.BigInteger.ZERO;
import static org.aion.crypto.HashUtil.shortHash;

public class AionBlockStore extends AbstractPowBlockstore<AionBlock, A0BlockHeader> {

    private static final Logger LOG = AionLoggerFactory.getLogger(LogEnum.DB.name());

    public IByteArrayKeyValueDatabase indexDS;
    DataSourceArray<List<BlockInfo>> index;
    public IByteArrayKeyValueDatabase blocksDS;
    ObjectDataSource<AionBlock> blocks;

    public AionBlockStore(IByteArrayKeyValueDatabase index, IByteArrayKeyValueDatabase blocks) {
        init(index, blocks);
    }

    public void init(IByteArrayKeyValueDatabase index, IByteArrayKeyValueDatabase blocks) {

        indexDS = index;
        this.index = new DataSourceArray<>(new ObjectDataSource<>(index, BLOCK_INFO_SERIALIZER));
        this.blocksDS = blocks;

        this.blocks = new ObjectDataSource<>(blocks, new Serializer<AionBlock, byte[]>() {
            @Override
            public byte[] serialize(AionBlock block) {
                return block.getEncoded();
            }

            @Override
            public AionBlock deserialize(byte[] bytes) {
                return new AionBlock(bytes);
            }
        });

    }

    public AionBlock getBestBlock() {

        Long maxLevel = getMaxNumber();
        if (maxLevel < 0) {
            return null;
        }

        AionBlock bestBlock = getChainBlockByNumber(maxLevel);
        if (bestBlock != null) {
            return bestBlock;
        }

        while (bestBlock == null) {
            --maxLevel;
            bestBlock = getChainBlockByNumber(maxLevel);
        }

        return bestBlock;
    }

    public byte[] getBlockHashByNumber(long blockNumber) {

        if (blockNumber < 0L || blockNumber >= index.size()) {
            return null;
        }

        List<BlockInfo> blockInfos = index.get((int) blockNumber);

        for (BlockInfo blockInfo : blockInfos) {
            if (blockInfo.isMainChain()) {
                return blockInfo.getHash();
            }
        }

        return null;
    }

    @Override
    public void flush() {
        blocks.flush();
        index.flush();
        try {
            if (!blocksDS.isAutoCommitEnabled()) {
                blocksDS.commit();
            }
        } catch (Exception e) {
            LOG.error("Unable to flush blocks data.", e);
        }
        try {
            if (!indexDS.isAutoCommitEnabled()) {
                indexDS.commit();
            }
        } catch (Exception e) {
            LOG.error("Unable to flush blocks index data.", e);
        }
    }

    @Override
    public void saveBlock(AionBlock block, BigInteger cummDifficulty, boolean mainChain) {
        addInternalBlock(block, cummDifficulty, mainChain);
    }

    private void addInternalBlock(AionBlock block, BigInteger cummDifficulty, boolean mainChain) {

        List<BlockInfo> blockInfos = block.getNumber() >= index.size() ? new ArrayList<BlockInfo>()
                : index.get((int) block.getNumber());

        BlockInfo blockInfo = new BlockInfo();
        blockInfo.setCummDifficulty(cummDifficulty);
        blockInfo.setHash(block.getHash());
        blockInfo.setMainChain(mainChain); // FIXME:maybe here I should force
                                           // reset main chain for all uncles on
                                           // that level

        blockInfos.add(blockInfo);
        index.set((int) block.getNumber(), blockInfos);

        blocks.put(block.getHash(), block);
    }

    public List<IAionBlock> getBlocksByNumber(long number) {

        List<IAionBlock> result = new ArrayList<>();

        if (number >= index.size()) {
            return result;
        }

        List<BlockInfo> blockInfos = index.get((int) number);

        for (BlockInfo blockInfo : blockInfos) {

            byte[] hash = blockInfo.getHash();
            IAionBlock block = blocks.get(hash);

            result.add(block);
        }
        return result;
    }

    @Override
    public AionBlock getChainBlockByNumber(long number) {
        int size = index.size();
        if (number < 0L || number >= size) {
            return null;
        }

        List<BlockInfo> blockInfos = index.get((int) number);

        for (BlockInfo blockInfo : blockInfos) {

            if (blockInfo.isMainChain()) {

                byte[] hash = blockInfo.getHash();
                return blocks.get(hash);
            }
        }

        return null;
    }

    @Override
    public AionBlock getBlockByHash(byte[] hash) {
        return blocks.get(hash);
    }

    @Override
    public boolean isBlockExist(byte[] hash) {
        return blocks.get(hash) != null;
    }

    @Override
    public BigInteger getTotalDifficultyForHash(byte[] hash) {
        IAionBlock block = this.getBlockByHash(hash);
        if (block == null) {
            return ZERO;
        }

        Long level = block.getNumber();
        List<BlockInfo> blockInfos = index.get(level.intValue());
        for (BlockInfo blockInfo : blockInfos) {
            if (Arrays.equals(blockInfo.getHash(), hash)) {
                return blockInfo.cummDifficulty;
            }
        }

        return ZERO;
    }

    @Override
    public BigInteger getTotalDifficulty() {
        long maxNumber = getMaxNumber();

        List<BlockInfo> blockInfos = index.get((int) maxNumber);
        for (BlockInfo blockInfo : blockInfos) {
            if (blockInfo.isMainChain()) {
                return blockInfo.getCummDifficulty();
            }
        }

        while (true) {
            --maxNumber;
            List<BlockInfo> infos = getBlockInfoForLevel(maxNumber);

            for (BlockInfo blockInfo : infos) {
                if (blockInfo.isMainChain()) {
                    return blockInfo.getCummDifficulty();
                }
            }
        }
    }

    @Override
    public long getMaxNumber() {
        // the index size is always >= 0
        long bestIndex = (long) index.size();
        return bestIndex - 1L;
    }

    @Override
    public List<byte[]> getListHashesEndWith(byte[] hash, long number) {

        List<AionBlock> blocks = getListBlocksEndWith(hash, number);
        List<byte[]> hashes = new ArrayList<>(blocks.size());

        for (IAionBlock b : blocks) {
            hashes.add(b.getHash());
        }

        return hashes;
    }

    @Override
    public List<A0BlockHeader> getListHeadersEndWith(byte[] hash, long qty) {

        List<AionBlock> blocks = getListBlocksEndWith(hash, qty);
        List<A0BlockHeader> headers = new ArrayList<>(blocks.size());

        for (IAionBlock b : blocks) {
            headers.add(b.getHeader());
        }

        return headers;
    }

    @Override
    public List<AionBlock> getListBlocksEndWith(byte[] hash, long qty) {
        return getListBlocksEndWithInner(hash, qty);
    }

    private List<AionBlock> getListBlocksEndWithInner(byte[] hash, long qty) {

        AionBlock block = this.blocks.get(hash);

        if (block == null) {
            return new ArrayList<>();
        }

        List<AionBlock> blocks = new ArrayList<>((int) qty);

        for (int i = 0; i < qty; ++i) {
            blocks.add(block);
            block = this.blocks.get(block.getParentHash());
            if (block == null) {
                break;
            }
        }

        return blocks;
    }

    @Override
    public void reBranch(AionBlock forkBlock) {

        IAionBlock bestBlock = getBestBlock();

        long currentLevel = Math.max(bestBlock.getNumber(), forkBlock.getNumber());

        // 1. First ensure that you are one the save level
        IAionBlock forkLine = forkBlock;
        if (forkBlock.getNumber() > bestBlock.getNumber()) {

            while (currentLevel > bestBlock.getNumber()) {
                List<BlockInfo> blocks = getBlockInfoForLevel(currentLevel);
                BlockInfo blockInfo = getBlockInfoForHash(blocks, forkLine.getHash());
                if (blockInfo != null) {
                    blockInfo.setMainChain(true);
                    setBlockInfoForLevel(currentLevel, blocks);
                }
                forkLine = getBlockByHash(forkLine.getParentHash());
                --currentLevel;
            }
        }

        IAionBlock bestLine = bestBlock;
        if (bestBlock.getNumber() > forkBlock.getNumber()) {

            while (currentLevel > forkBlock.getNumber()) {

                List<BlockInfo> blocks = getBlockInfoForLevel(currentLevel);
                BlockInfo blockInfo = getBlockInfoForHash(blocks, bestLine.getHash());
                if (blockInfo != null) {
                    blockInfo.setMainChain(false);
                    setBlockInfoForLevel(currentLevel, blocks);
                }
                bestLine = getBlockByHash(bestLine.getParentHash());
                --currentLevel;
            }
        }

        // 2. Loop back on each level until common block
        while (!bestLine.isEqual(forkLine)) {

            List<BlockInfo> levelBlocks = getBlockInfoForLevel(currentLevel);
            BlockInfo bestInfo = getBlockInfoForHash(levelBlocks, bestLine.getHash());
            if (bestInfo != null) {
                bestInfo.setMainChain(false);
                setBlockInfoForLevel(currentLevel, levelBlocks);
            }

            BlockInfo forkInfo = getBlockInfoForHash(levelBlocks, forkLine.getHash());
            if (forkInfo != null) {
                forkInfo.setMainChain(true);
                setBlockInfoForLevel(currentLevel, levelBlocks);
            }

            bestLine = getBlockByHash(bestLine.getParentHash());
            forkLine = getBlockByHash(forkLine.getParentHash());

            --currentLevel;
        }

    }

    @Override
    public void revert(long previousLevel) {

        IAionBlock bestBlock = getBestBlock();

        long currentLevel = bestBlock.getNumber();

        // ensure that the given level is lower than current
        if (previousLevel >= currentLevel) {
            return;
        }

        // walk back removing blocks greater than the given level value
        IAionBlock bestLine = bestBlock;
        while (currentLevel > previousLevel) {
            index.remove((int) currentLevel);
            blocks.delete(bestLine.getHash());
            bestLine = getBlockByHash(bestLine.getParentHash());
            --currentLevel;
        }

        // set the block at the given level as the main chain
        List<BlockInfo> blocks = getBlockInfoForLevel(previousLevel);
        BlockInfo blockInfo = getBlockInfoForHash(blocks, bestLine.getHash());
        if (blockInfo != null) {
            blockInfo.setMainChain(true);
            setBlockInfoForLevel(previousLevel, blocks);
        }
    }

    public List<byte[]> getListHashesStartWith(long number, long maxBlocks) {

        List<byte[]> result = new ArrayList<>();

        int i;
        for (i = 0; i < maxBlocks; ++i) {
            List<BlockInfo> blockInfos = index.get((int) number);
            if (blockInfos == null) {
                break;
            }

            for (BlockInfo blockInfo : blockInfos) {
                if (blockInfo.isMainChain()) {
                    result.add(blockInfo.getHash());
                    break;
                }
            }

            ++number;
        }
        maxBlocks -= i;

        return result;
    }

    public static class BlockInfo implements Serializable {

        public BlockInfo() {};

        public BlockInfo(byte[] ser) {
            RLPList outerList = RLP.decode2(ser);

            // should we throw?
            if (outerList.isEmpty())
                return;

            RLPList list = (RLPList) outerList.get(0);
            this.hash = list.get(0).getRLPData();
            this.cummDifficulty = ByteUtil.bytesToBigInteger(list.get(1).getRLPData());

            byte[] boolData = list.get(2).getRLPData();
            this.mainChain = !(boolData == null || boolData.length == 0) && boolData[0] == (byte) 0x1;
        }

        private static final long serialVersionUID = 7279277944605144671L;

        public byte[] hash;

        public BigInteger cummDifficulty;

        public boolean mainChain;

        public byte[] getHash() {
            return hash;
        }

        public void setHash(byte[] hash) {
            this.hash = hash;
        }

        public BigInteger getCummDifficulty() {
            return cummDifficulty;
        }

        public void setCummDifficulty(BigInteger cummDifficulty) {
            this.cummDifficulty = cummDifficulty;
        }

        public boolean isMainChain() {
            return mainChain;
        }

        public void setMainChain(boolean mainChain) {
            this.mainChain = mainChain;
        }

        public byte[] getEncoded() {
            byte[] hashElement = RLP.encodeElement(hash);
            byte[] cumulativeDiffElement = RLP.encodeElement(cummDifficulty.toByteArray());
            byte[] mainChainElement = RLP.encodeByte(mainChain ? (byte) 0x1 : (byte) 0x0);
            return RLP.encodeList(hashElement, cumulativeDiffElement, mainChainElement);
        }
    }

    private static class MigrationRedirectingInputStream extends ObjectInputStream {

        public MigrationRedirectingInputStream(InputStream in) throws IOException {
            super(in);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            if (desc.getName().equals("org.aion.db.a0.AionBlockStore$BlockInfo"))
                return BlockInfo.class;
            return super.resolveClass(desc);
        }
    }

    /**
     * Called by {@link AionBlockStore#BLOCK_INFO_SERIALIZER} for now, on main-net launch
     * we should default to this class.
     */
    public static final Serializer<List<BlockInfo>, byte[]> BLOCK_INFO_RLP_SERIALIZER = new Serializer<List<BlockInfo>,byte[]>() {
        @Override
        public byte[] serialize(List<BlockInfo> object) {
            byte[][] infoList = new byte[object.size()][];
            int i = 0;
            for (BlockInfo b : object) {
                infoList[i] = b.getEncoded();
                i++;
            }
            return RLP.encodeList(infoList);
        }

        @Override
        public List<BlockInfo> deserialize(byte[] stream) {
            RLPList list = (RLPList) RLP.decode2(stream).get(0);
            List<BlockInfo> res = new ArrayList<>(list.size());

            for (int i = 0; i < list.size(); i++) {
                res.add(new BlockInfo(list.get(i).getRLPData()));
            }
            return res;
        }
    };

    public static final Serializer<List<BlockInfo>, byte[]> BLOCK_INFO_SERIALIZER = new Serializer<List<BlockInfo>, byte[]>() {

        @Override
        public byte[] serialize(List<BlockInfo> value) {
            return BLOCK_INFO_RLP_SERIALIZER.serialize(value);
        }

        @SuppressWarnings("unchecked")
        @Override
        public List<BlockInfo> deserialize(byte[] bytes) {
            try {
                return BLOCK_INFO_RLP_SERIALIZER.deserialize(bytes);
            } catch (Exception e) {
                // fallback logic for old block infos
                try {
                    ByteArrayInputStream bis = new ByteArrayInputStream(bytes, 0, bytes.length);
                    ObjectInputStream ois = new MigrationRedirectingInputStream(bis);
                    return (List<BlockInfo>) ois.readObject();
                } catch (IOException | ClassNotFoundException e2) {
                    throw new RuntimeException(e2);
                }
            }
        }
    };

    public void printChain() {

        Long number = getMaxNumber();

        for (int i = 0; i < number; ++i) {
            List<BlockInfo> levelInfos = index.get(i);

            if (levelInfos != null) {
                System.out.print(i);
                for (BlockInfo blockInfo : levelInfos) {
                    if (blockInfo.isMainChain()) {
                        System.out.print(" [" + shortHash(blockInfo.getHash()) + "] ");
                    } else {
                        System.out.print(" " + shortHash(blockInfo.getHash()) + " ");
                    }
                }
                System.out.println();
            }
        }
    }

    private List<BlockInfo> getBlockInfoForLevel(long level) {
        return index.get((int) level);
    }

    private void setBlockInfoForLevel(long level, List<BlockInfo> infos) {
        index.set((int) level, infos);
    }

    private static BlockInfo getBlockInfoForHash(List<BlockInfo> blocks, byte[] hash) {

        for (BlockInfo blockInfo : blocks) {
            if (Arrays.equals(hash, blockInfo.getHash())) {
                return blockInfo;
            }
        }

        return null;
    }

    @Override
    public void load() {
    }

    @Override
    public void close() {
        try {
            indexDS.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            blocksDS.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
