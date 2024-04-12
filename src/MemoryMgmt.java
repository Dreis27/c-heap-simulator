// STUDENT ID: 38880059

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/*
 * MEMORY MANAGEMENT SIMULATOR
 * 
 * Memory is an array of 8192 bytes where different sections of that memory get allocated, freed and connected in a way that forms free lists.
 * 
 * malloc() and free() functions should operate fairly similar to C.
 * No helper data structures (except for FreeList class that is used for storing head and tail pointers) or any other data types, 
 * that are not present in the C implementation, are used in those functions.
 * 
 * sbrk() is further away from C implementation mainly due to the requirement to create a new array each time the memory is allocated using sbrk().
 * Also I didn't manage to fix all errors in sbrk(). One error can be observed in TEST NUMBER 11.
 */

public class MemoryMgmt{

    byte memoryArray[];
    
    // 6 free lists for fixed sizes
    FreeList list16;
    FreeList list32;
    FreeList list64;
    FreeList list128;
    FreeList list256;
    FreeList list512;

    // separate free list for memory that has been freed after being allocated by sbrk
    FreeList sbrkFreeList;
    // free list for variable sizes 
    FreeList variableLengthList;
    // free list with one node that represents the free memory thats between the last allocated address and 8192
    FreeList freeMemoryPart;

    FreeList lists[] = new FreeList[6];
    int freeMemoryPartLength = 8183;

    // Data structure that stores properties of each allocation made by calling sbrk()
    // That is done in order to be able to translate virtual sbrk addresses into real addreses in arrays
    List<SbrkReturnType> allocations = new ArrayList<>();
    
    public static void main(String args[]){
        MemoryMgmt myMemoryMgmt = new MemoryMgmt();
        myMemoryMgmt.print();
    }

    public MemoryMgmt(){
        memoryArray = new byte[8192];
        
        // Initial metadata of the block of memory
        writeBooleanAndIntToByteArray(memoryArray, true, 0, 1); // guard start
        writeBooleanAndIntToByteArray(memoryArray, false, 0, 8188); //guard end
        writeBooleanAndIntToByteArray(memoryArray, false, freeMemoryPartLength, 8184);
        writeBooleanAndIntToByteArray(memoryArray, false, freeMemoryPartLength, 5);

        list16 = new FreeList(0, 0, 16);
        list32 = new FreeList(0, 0, 32);
        list64 = new FreeList(0, 0, 64);
        list128 = new FreeList(0, 0, 128);
        list256 = new FreeList(0, 0, 256);
        list512 = new FreeList(0, 0, 512);

        variableLengthList = new FreeList(0, 0, 0);
        sbrkFreeList = new FreeList(0, 0, 0);

        // memoryArray[0] is reserved for the null pointer so I start allocating metadata starting at memoryArray[1]
        // this will lead to most pointers being odd values
        freeMemoryPart = new FreeList(1, 1, freeMemoryPartLength);

        lists[0] = list16;
        lists[1] = list32;
        lists[2] = list64;
        lists[3] = list128;
        lists[4] = list256;
        lists[5] = list512;
    }

    public int malloc(int size){
        System.out.print("\nRequesting " + size + " bytes of memory...");

        // Find a list where the requested size would fit the best
        FreeList list = findBestFit(size);

        // Find space in the list for allocation
        int foundSpaceInFreeList = searchFreeListForSpace(list, size);
        //System.out.println("Free space found at address: "+foundSpaceInFreeList);
        if(foundSpaceInFreeList == freeMemoryPart.head) list = freeMemoryPart;

        // No space found
        if(foundSpaceInFreeList == 0 || size>(freeMemoryPartLength-16)){
            int sbrkFreeSpace = searchSbrkForFreeSpace(size);
            if(sbrkFreeSpace == 0){
                //Requesting further memory block
                System.out.print("\nMemory limit exceeded, requesting more memory...");
                SbrkReturnType sbrkBlock = sbrk(size);
                System.out.print("memory allocated.\n");
                System.out.println("Pointer: " + (sbrkBlock.pointer+8));
                return sbrkBlock.pointer+8;
            } else {
                allocatetoSbrkFreeList(size, sbrkFreeSpace);
                System.out.print("memory allocated.\n");
                System.out.println("Pointer: " + (sbrkFreeSpace+8));
                return sbrkFreeSpace+8;
            }
        }
        // Allocate requested memory in the chosen list
        int newPointer = allocateUsedMemory(size, foundSpaceInFreeList, list);

        System.out.print("memory allocated.\n");
        System.out.println("Pointer: " + newPointer);

        return newPointer;
    }

    public void free(int address){
        System.out.print("\nFreeing pointer: "+address + " ...");

        if(address>8192) {
            freeSbrk(address);
            return;
        }
        if(readBooleanFromByteArray(memoryArray, address-4) == false){
            System.out.println("Exception triggered in thread. Exiting.");
            return;
        }
        // Get length of the block we want to free
        int length = readIntFromByteArray(memoryArray, address-4);
        
        // Check adjacent memory and coalesce if it's empty
        if(readBooleanFromByteArray(memoryArray, address-8) == false){
            coalesceLeft(address, readIntFromByteArray(memoryArray, address-8));
            return;
        } else if (readBooleanFromByteArray(memoryArray, address+length-4) == false){
            coalesceRight(address, readIntFromByteArray(memoryArray, address+length-4));
            return;
        }

        // Set block flag to free
        writeBooleanAndIntToByteArray(memoryArray, false, length, address-4);

        // Choose list for the new free block
        FreeList list = chooseList(length);

        // Appending the new block to the free list
        // set block's prev pointer to the tail of our free list
        writeIntToByteArray(memoryArray, list.tail, address);

        // set block's next pointer to null
        writeIntToByteArray(memoryArray, 0, address+4);

        // Set block flag to free
        int prevLenBlockLen = readIntFromByteArray(memoryArray, address+(length-8));
        writeBooleanAndIntToByteArray(memoryArray, false, prevLenBlockLen, address+(length-8));

        if(list.tail != 0) {
            //Free list is not empty
            // Set previous block's next pointer to our new block
            writeIntToByteArray(memoryArray, address-8, list.tail+12);

            // Move tail to new block
            list.tail = address-8;
            System.out.print("memory freed.\n");
            return;
        }

        //Free list is empty
        //Move tail to new block
        list.tail = address-8;
        //Move head to new block
        list.head = address-8;
        System.out.print("memory freed.\n");
    }

    public SbrkReturnType sbrk(int size) {
        int sizeToAccomodateMetadata = size+16;
        int newSize = calculateChunkSize(sizeToAccomodateMetadata);
        int pointer = calculateNewSbrkAddress(newSize);
        byte[] memoryChunk = new byte[newSize];
        
        // Add guards at both sides
        writeBooleanAndIntToByteArray(memoryChunk, true, 0, 0);
        writeBooleanAndIntToByteArray(memoryChunk, true, 0, newSize-4);

        // Add used block metadata
        writeBooleanAndIntToByteArray(memoryChunk, true, newSize-8, 4);
        writeBooleanAndIntToByteArray(memoryChunk, true, newSize-8, newSize-8);
        
        SbrkReturnType newAllocation = new SbrkReturnType(pointer, memoryChunk, newSize-8);

        // Store the new created allocation
        allocations.add(newAllocation);

        return newAllocation;
    }
    public void print(){

        // First 4 tests are the required one from the coursework description
        System.out.println("\n\nRUNNING TEST NUMBER 1...\n");
        MemoryMgmt memoryMgmt1 = new MemoryMgmt();
        System.out.println("HEAD pointer: "+memoryMgmt1.freeMemoryPart.head+"\n");
        int pointer = memoryMgmt1.malloc(28);
        writeStringToByteArray(memoryMgmt1.memoryArray, "Hello World!", pointer);
        String string = readStringFromByteArray(memoryMgmt1.memoryArray, pointer);
        System.out.println(string);

        System.out.println("\n------------------------------------------------------------------");
        System.out.println("\n\nRUNNING TEST NUMBER 2...\n");
        MemoryMgmt memoryMgmt2 = new MemoryMgmt();
        System.out.println("HEAD pointer: "+memoryMgmt2.freeMemoryPart.head+"\n");
        int pointer1 = memoryMgmt2.malloc(28);
        int pointer2 = memoryMgmt2.malloc(1024);
        int pointer3 = memoryMgmt2.malloc(28);
        memoryMgmt2.free(pointer2);

        /*
         * this (memoryMgmt2.malloc(512); ) won't go to the place where 1024 was freed because of the way I manage allocation of certain fixed sizes
         * (see test 6 for a different behavior example example)
         */ 
        int pointer4 = memoryMgmt2.malloc(512); 
        memoryMgmt2.free(pointer1);
        memoryMgmt2.free(pointer3);
        memoryMgmt2.free(pointer4);

        System.out.println("\n------------------------------------------------------------------");
        System.out.println("\n\nRUNNING TEST NUMBER 3...\n");
        MemoryMgmt memoryMgmt3 = new MemoryMgmt();
        System.out.println("HEAD pointer: "+memoryMgmt3.freeMemoryPart.head+"\n");
        int pointer5 = memoryMgmt3.malloc(7168);
        int pointer6 = memoryMgmt3.malloc(1024);
        memoryMgmt3.free(pointer5);
        memoryMgmt3.free(pointer6);

        System.out.println("\n------------------------------------------------------------------");
        System.out.println("\n\nRUNNING TEST NUMBER 4...\n");
        MemoryMgmt memoryMgmt4 = new MemoryMgmt();
        System.out.println("HEAD pointer: "+memoryMgmt4.freeMemoryPart.head+"\n");
        int pointer7 = memoryMgmt4.malloc(1024);
        int pointer8 = memoryMgmt4.malloc(28);
        memoryMgmt4.free(pointer8);
        memoryMgmt4.free(pointer8);

        // Test 5 to show the ability of free memory blocks to be efficiently coalesced with their neighbouring free blocks, 
        // and that blocks can be freed I any order without running into issues
        System.out.println("\n------------------------------------------------------------------");
        System.out.println("\n\nRUNNING TEST NUMBER 5...\n");
        MemoryMgmt memoryMgmt5 = new MemoryMgmt();
        System.out.println("HEAD pointer: "+memoryMgmt5.freeMemoryPart.head+"\n");
        int pointer9 = memoryMgmt5.malloc(16);
        int pointer10 = memoryMgmt5.malloc(32);
        int pointer11 = memoryMgmt5.malloc(64);
        int pointer12 = memoryMgmt5.malloc(128);
        int pointer13 = memoryMgmt5.malloc(256);
        int pointer14 = memoryMgmt5.malloc(512);
        int pointer15 = memoryMgmt5.malloc(300);
        memoryMgmt5.free(pointer13);
        memoryMgmt5.free(pointer10);
        memoryMgmt5.free(pointer11);
        memoryMgmt5.free(pointer12);
        memoryMgmt5.free(pointer9);
        memoryMgmt5.free(pointer14);
        memoryMgmt5.free(pointer15);
        int pointer16 = memoryMgmt5.malloc(7000);

        // Test 6 shows how new allocated memory chunks can take place of previously freed ones, where we can also fit multiple blocks of smaller sizes 
        System.out.println("\n------------------------------------------------------------------");
        System.out.println("\n\nRUNNING TEST NUMBER 6...\n");
        MemoryMgmt memoryMgmt6 = new MemoryMgmt();
        System.out.println("HEAD pointer: "+memoryMgmt6.freeMemoryPart.head+"\n");
        int pointer17 = memoryMgmt6.malloc(32);
        int pointer18 = memoryMgmt6.malloc(2048);
        int pointer19 = memoryMgmt6.malloc(20);
        memoryMgmt6.free(pointer18);
        int pointer20 = memoryMgmt6.malloc(513);
        int pointer21 = memoryMgmt6.malloc(492);
        memoryMgmt6.free(pointer21);
        memoryMgmt6.free(pointer20);
        memoryMgmt6.free(pointer19);
        memoryMgmt6.free(pointer17);
        
        // Test 7 shows the behaviour of the system after having to call sbrk more than once, as well as the possibility to reuse free memory allocated by calling sbrk
        // Also shows that freeing the same pointer to the sbrk array more than once triggers an exception
        System.out.println("\n------------------------------------------------------------------");
        System.out.println("\n\nRUNNING TEST NUMBER 7...\n");
        MemoryMgmt memoryMgmt7 = new MemoryMgmt();
        System.out.println("HEAD pointer: "+memoryMgmt7.freeMemoryPart.head+"\n");
        int pointer22 = memoryMgmt7.malloc(8100);
        int pointer23 = memoryMgmt7.malloc(100);
        int pointer24 = memoryMgmt7.malloc(10000);
        memoryMgmt7.free(pointer23);
        int pointer25 = memoryMgmt7.malloc(100);
        memoryMgmt7.free(pointer24);
        memoryMgmt7.free(pointer25);
        memoryMgmt7.free(pointer25);

        // Test 8 shows how it is possible to fit multiple used chunks into the memory space that got available after calling sbrk
        System.out.println("\n------------------------------------------------------------------");
        System.out.println("\n\nRUNNING TEST NUMBER 8...\n");
        MemoryMgmt memoryMgmt8 = new MemoryMgmt();
        System.out.println("HEAD pointer: "+memoryMgmt8.freeMemoryPart.head+"\n");
        int pointer27 = memoryMgmt8.malloc(8160);
        int pointer28 = memoryMgmt8.malloc(10000);
        memoryMgmt8.free(pointer28);
        int pointer29 = memoryMgmt8.malloc(1000);
        int pointer30 = memoryMgmt8.malloc(3500);
        int pointer31 = memoryMgmt8.malloc(2999);
        memoryMgmt8.free(pointer29);
        memoryMgmt8.free(pointer30);
        memoryMgmt8.free(pointer31);

        // Test 9 shows how memory will always be allocated to our original available space of 8192 bytes even if there is extra space from sbrk call
        System.out.println("\n------------------------------------------------------------------");
        System.out.println("\n\nRUNNING TEST NUMBER 9...\n");
        MemoryMgmt memoryMgmt9 = new MemoryMgmt();
        System.out.println("HEAD pointer: "+memoryMgmt9.freeMemoryPart.head+"\n");
        int pointer32 = memoryMgmt9.malloc(8160);
        int pointer33 = memoryMgmt9.malloc(5000);
        memoryMgmt9.free(pointer33);
        memoryMgmt9.free(pointer32);
        int pointer34 = memoryMgmt9.malloc(5000);
        memoryMgmt9.free(pointer34);

        // Test 10 demonstartes a little more pressure put on reusing the memory part that was supplied by calling sbrk
        System.out.println("\n------------------------------------------------------------------");
        System.out.println("\n\nRUNNING TEST NUMBER 10...\n");
        MemoryMgmt memoryMgmt10 = new MemoryMgmt();
        System.out.println("HEAD pointer: "+memoryMgmt10.freeMemoryPart.head+"\n");
        int pointer35 = memoryMgmt10.malloc(8160);
        int pointer36 = memoryMgmt10.malloc(5000);
        memoryMgmt10.free(pointer36);
        int pointer37 = memoryMgmt10.malloc(700);
        int pointer38 = memoryMgmt10.malloc(1000);
        int pointer39 = memoryMgmt10.malloc(1024);
        memoryMgmt10.free(pointer37);
        memoryMgmt10.free(pointer38);
        memoryMgmt10.free(pointer39);
        int pointer40 = memoryMgmt10.malloc(100);
        int pointer41 = memoryMgmt10.malloc(200);

        // Test 11 shows similar scenario to test 10, however this time the program runs into an issue
        System.out.println("\n------------------------------------------------------------------");
        System.out.println("\n\nRUNNING TEST NUMBER 11...\n");
        MemoryMgmt memoryMgmt11 = new MemoryMgmt();
        System.out.println("HEAD pointer: "+memoryMgmt11.freeMemoryPart.head+"\n");
        int pointer42 = memoryMgmt11.malloc(8160);
        int pointer43 = memoryMgmt11.malloc(5000);
        memoryMgmt11.free(pointer43);
        int pointer44 = memoryMgmt11.malloc(700);
        int pointer45 = memoryMgmt11.malloc(1000);
        int pointer46 = memoryMgmt11.malloc(1024);
        memoryMgmt11.free(pointer46);
        memoryMgmt11.free(pointer45);
        memoryMgmt11.free(pointer44);
        int pointer47 = memoryMgmt11.malloc(1024);
        int pointer48 = memoryMgmt11.malloc(1024);
        int pointer49 = memoryMgmt11.malloc(1024);
        memoryMgmt11.free(pointer48);
        memoryMgmt11.free(pointer49);
        memoryMgmt11.free(pointer47);
    }

    // Freeing the memory allocated by calling sbrk()
    public void freeSbrk(int address){
        byte sbrkArray[] = null;
        int newAddress = 0;
        SbrkReturnType alloc = null;

        // Find which array does the address belong to
        for (SbrkReturnType allocation : allocations) {
            if(address-8 >= allocation.pointer && address-8 <= (allocation.pointer+allocation.size)) {
                sbrkArray = allocation.array;
                newAddress = address - allocation.pointer-8;
                alloc = allocation;
                break;
            }
        }
        if(sbrkArray==null) {
            System.out.println("This address doesn't exist.");
            return;
        }
        
        if(readBooleanFromByteArray(sbrkArray, newAddress+4)==false) {
            System.out.println("Exception triggered in thread. Exiting.");
            return;
        }

        // Get length of the block we want to free
        int thisSize = readIntFromByteArray(sbrkArray, newAddress+4);

        // Check adjacent memory and coalesce if it's empty
        if(readBooleanFromByteArray(sbrkArray, newAddress) == false){
            coalesceSbrkLeft(alloc, address, readIntFromByteArray(sbrkArray, newAddress));
            return;

        } else if (readBooleanFromByteArray(sbrkArray, newAddress+thisSize+4) == false){
            coalesceSbrkRight(alloc, address, readIntFromByteArray(sbrkArray, newAddress+thisSize+4));
            return;
        }

        // Find which array does the previous block in the list belong to
        int prevPointer  = readIntFromByteArray(sbrkArray, newAddress+8);
        SbrkReturnType prevPointerAlloc = null;
        for (SbrkReturnType allocation : allocations) {
            if(prevPointer >= allocation.pointer && prevPointer <= (allocation.pointer+allocation.size)) {
                    prevPointerAlloc = allocation;
                    break;
            }
        }

        //Updating metadata to make this a free block
        writeBooleanAndIntToByteArray(sbrkArray, false, thisSize, newAddress+4); // size and 'used' flag
        writeIntToByteArray(sbrkArray, sbrkFreeList.tail+ alloc.pointer, newAddress+8); // prev pointer
        writeIntToByteArray(sbrkArray, 0, newAddress+12); // next pointer
        writeBooleanAndIntToByteArray(sbrkArray, false, thisSize, newAddress+thisSize); // size and 'used' flag

        //Move previous element's next pointer to this free block
        if(prevPointer !=0) {
            writeIntToByteArray(prevPointerAlloc.array, newAddress+alloc.pointer, prevPointer+8-prevPointerAlloc.pointer);
        }   

        // Move tail and head
        if(sbrkFreeList.head == 0) sbrkFreeList.head = newAddress + alloc.pointer;
        sbrkFreeList.tail = newAddress + alloc.pointer;

        System.out.print("memory freed.\n");
    }

    // Function coalesces the block with a free block on it's left (in the memory array)
    public void coalesceLeft(int addressInMemoryArray, int prevLength){
        System.out.print(" coalescing... ");

        FreeList leftBlockList = chooseList(prevLength);
        
        // Get length of current block
        int thisLen = readIntFromByteArray(memoryArray, addressInMemoryArray-4);

        // Edit current block's length and 'used' flag
        writeBooleanAndIntToByteArray(memoryArray, false, prevLength+thisLen, addressInMemoryArray+thisLen-8);
        // Edit left block's length 
        writeBooleanAndIntToByteArray(memoryArray, false, prevLength+thisLen, addressInMemoryArray-prevLength-4);

        // When coalescing, blocks get removed from their fixed size lists (if they are there initially), and moved to the variableLengthList
        if(leftBlockList != variableLengthList){
            
            // Removing the left block from the free list it was before to move the new coalesced block to the new list
            int prevBlockAddress = readIntFromByteArray(memoryArray, addressInMemoryArray-prevLength);
            int nextBlockAddress = readIntFromByteArray(memoryArray, addressInMemoryArray-prevLength+4);
            int addressOfLeftBlock = addressInMemoryArray-8-prevLength;
        
            // In case the block was at the start/end of the list
            if(leftBlockList.head == leftBlockList.tail){
                leftBlockList.head = 0;
                leftBlockList.tail = 0;
            } else if (leftBlockList.head == addressOfLeftBlock){
                leftBlockList.head = nextBlockAddress;
            } else if (leftBlockList.tail == addressOfLeftBlock){
                leftBlockList.tail = prevBlockAddress;
            }

            if(prevBlockAddress != 0){
                // Move previous block's next pointer to the next block
                writeIntToByteArray(memoryArray, nextBlockAddress, prevBlockAddress+12);
            }
            if(nextBlockAddress != 0){
                //Move next block's prev pointer to previous block
                writeIntToByteArray(memoryArray, prevBlockAddress, nextBlockAddress+8);
            }

            //Adding new block to the variableLengthList
            if(variableLengthList.head == 0){
                variableLengthList.head = addressOfLeftBlock;
                variableLengthList.tail = addressOfLeftBlock;
            } else {
                writeIntToByteArray(memoryArray, addressOfLeftBlock, variableLengthList.tail+12);
                variableLengthList.tail = addressOfLeftBlock;
            }
        
        }
        System.out.print("memory freed.\n");
        // Check again if we can calesce with the block on the right
        if (readBooleanFromByteArray(memoryArray, addressInMemoryArray+thisLen-4) == false){
           coalesceRight(addressInMemoryArray-prevLength, readIntFromByteArray(memoryArray, addressInMemoryArray+thisLen-4));
            return;
        }
    }

    // Function coalesces the block with a free block on it's right (in the memory array)
    public void coalesceRight(int addressInMemoryArray, int nextLength){
        System.out.print(" coalescing... ");;

        FreeList rightBlockList = chooseList(nextLength);

        // Get length of current block
        int thisLen = readIntFromByteArray(memoryArray, addressInMemoryArray-4);

        // Create free block metadata for current block
        writeBooleanAndIntToByteArray(memoryArray, false, nextLength+thisLen, addressInMemoryArray-4);
        writeIntToByteArray(memoryArray, 0, addressInMemoryArray);
        writeIntToByteArray(memoryArray, 0, addressInMemoryArray+4);

        int rightBlockAddress = addressInMemoryArray+thisLen-8;

        // If the block on the right is the freeMemoryPart, we just merge our block with it
        if(nextLength == freeMemoryPartLength){
            freeMemoryPart.head = addressInMemoryArray-8;
            freeMemoryPartLength = freeMemoryPartLength + thisLen;

            writeBooleanAndIntToByteArray(memoryArray, false, freeMemoryPartLength, freeMemoryPart.head+4);

        } else if (rightBlockList == variableLengthList){
            // get right block's next pointer
            int nextBlockAddress = readIntFromByteArray(memoryArray, rightBlockAddress+12);
            // get right block's prev pointer
            int prevBlockAddress = readIntFromByteArray(memoryArray, rightBlockAddress+8);

            //Edit length
            writeBooleanAndIntToByteArray(memoryArray, false, thisLen+nextLength, addressInMemoryArray+thisLen+nextLength-8);

            // Move next block's prev pointer to current block
            if(nextBlockAddress !=0) writeIntToByteArray(memoryArray, addressInMemoryArray-8, nextBlockAddress+8);
            // Move previous block's next pointer to current block
            if(prevBlockAddress !=0) writeIntToByteArray(memoryArray, addressInMemoryArray-8, prevBlockAddress+12);

            //Move current block's prev pointer to the previous block
            writeIntToByteArray(memoryArray, prevBlockAddress, addressInMemoryArray);
            //Move current block's next pointer to the next block
            writeIntToByteArray(memoryArray, nextBlockAddress, addressInMemoryArray+4);

            if(variableLengthList.head == rightBlockAddress) variableLengthList.head = addressInMemoryArray-8;
            if(variableLengthList.tail == rightBlockAddress) variableLengthList.tail = addressInMemoryArray-8;

        } else {
            int nextBlockAddress = readIntFromByteArray(memoryArray, rightBlockAddress+12);
            int prevBlockAddress = readIntFromByteArray(memoryArray, rightBlockAddress+8);

            //Edit length metadata
            writeBooleanAndIntToByteArray(memoryArray, false, thisLen+nextLength, addressInMemoryArray+thisLen+nextLength-8);

            // In case the block was at the start/end of the list
            if(rightBlockList.head == rightBlockList.tail){
                rightBlockList.head = 0;
                rightBlockList.tail = 0;
            } else if (rightBlockList.head == rightBlockAddress){
                rightBlockList.head = nextBlockAddress;
            } else if (rightBlockList.tail == rightBlockAddress){
                rightBlockList.tail = prevBlockAddress;
            }

            if(prevBlockAddress != 0){
                // Move previous block's next pointer to the next block
                writeIntToByteArray(memoryArray, nextBlockAddress, prevBlockAddress+12);
            }
            if(nextBlockAddress != 0){
                //Move next block's prev pointer to previous block
                writeIntToByteArray(memoryArray, prevBlockAddress, nextBlockAddress+8);
            }

            //adding new block to the variableLengthList
            if(variableLengthList.head == 0){
                variableLengthList.head = addressInMemoryArray-8;
                variableLengthList.tail = addressInMemoryArray-8;
            } else {
                writeIntToByteArray(memoryArray, variableLengthList.tail+12, addressInMemoryArray-8);
                variableLengthList.tail = addressInMemoryArray-8;
            }
    
        }
        System.out.print("memory freed.\n");
    }

    // Finds best fitting free space in the given free list. Returns 0 if no free space found
    public int searchFreeListForSpace(FreeList list, int size){
        if(list == freeMemoryPart || list.head == 0) {
            if(freeMemoryPartLength >= (size+8)){
                return freeMemoryPart.head;
            } else {
                list = variableLengthList;         
            }
        }

        if(list == variableLengthList){
            int bestFitAddress = 0;
            int minSizeDifference = Integer.MAX_VALUE;

            //get pointer to the first block in the list
            int temp = list.head;
            while(temp != 0){ //traverse the list until best fit is found
                int nextBlockLength = readIntFromByteArray(memoryArray, temp+4);
                if(nextBlockLength == (size+8)){
                    return temp; 
                } else if (nextBlockLength >= (size + 24) && nextBlockLength - (size + 24) < minSizeDifference){
                    bestFitAddress = temp;
                    minSizeDifference = nextBlockLength - (size + 24);
                }
                temp = readIntFromByteArray(memoryArray, temp+12);
            }

            if(bestFitAddress != 0){
                // return the best fitting block from the variable list that is at least 24 bytes larger than size so that we are able to split that block in 2 free blocks
                return bestFitAddress; 
            }
            //no space found in the list
            if(freeMemoryPartLength >= (size+8)){
                return freeMemoryPart.head;
            } else {return 0;}
        }
        int addressInArray = list.head;
        return (addressInArray);
    }

    // Allocates a used memory chunk of a given size to the given address
    public int allocateUsedMemory(int chunkSize, int address, FreeList list){
        int thisLen = readIntFromByteArray(memoryArray, address+4);

        // Edit 'used' flag of the memory block
        writeBooleanAndIntToByteArray(memoryArray, true, chunkSize+8, address+4);

        if(list == freeMemoryPart) {

            // Move the start of my free memory space to the right to make space for the used block
            // This involves creating new metadate at new positions and moving head, tail
            writeBooleanAndIntToByteArray(memoryArray, true, chunkSize+8, address+chunkSize+8);

            freeMemoryPart.head = freeMemoryPart.head + 8 + chunkSize;
            freeMemoryPart.tail = freeMemoryPart.tail + 8 + chunkSize;
            freeMemoryPartLength = freeMemoryPartLength - (chunkSize+8);

            writeBooleanAndIntToByteArray(memoryArray, false, freeMemoryPartLength, freeMemoryPart.head + 4);
            writeIntToByteArray(memoryArray, 0, freeMemoryPart.head+8);
            writeIntToByteArray(memoryArray,0, freeMemoryPart.head+12);

            return (address+8);
        }

        else if(list == variableLengthList) {
             //Address of previous block in the list
             int prevAddress = readIntFromByteArray(memoryArray, address+8);
             //Address of next block in the list
             int nextAddress = readIntFromByteArray(memoryArray, address+12);

             // Edit 'used' flag and size metadata of the free block
             writeBooleanAndIntToByteArray(memoryArray, true, thisLen, address+thisLen); 
             writeBooleanAndIntToByteArray(memoryArray, true, thisLen, address+4);

             if(thisLen >= (chunkSize+24)){ //In this case the found free block is split into two
   
                int lenghtOfRemainingBlock = thisLen-chunkSize-8;

                // Creating new metadata blocks for the newly formed free chunk 
                writeBooleanAndIntToByteArray(memoryArray, true, chunkSize+8, address+4);
                writeBooleanAndIntToByteArray(memoryArray, true, chunkSize+8, address+chunkSize+8);
                writeBooleanAndIntToByteArray(memoryArray, false, lenghtOfRemainingBlock, address+chunkSize+12);
                writeIntToByteArray(memoryArray, prevAddress, address+chunkSize+16);
                writeIntToByteArray(memoryArray, nextAddress, address+chunkSize+20);
                writeBooleanAndIntToByteArray(memoryArray, false, lenghtOfRemainingBlock, address+thisLen);

                // Edit pointers to fit the new block into the list
                if(nextAddress != 0) {
                    writeIntToByteArray(memoryArray, address+chunkSize+8, nextAddress+8);
                }
                else {variableLengthList.tail = address+chunkSize+8;}
                
                if(prevAddress !=0) {
                    writeIntToByteArray(memoryArray, address+chunkSize+8, prevAddress+12);
                } else {variableLengthList.head = address+chunkSize+8;}

                return (address+8);
             } 

            // Edit pointers to fit the new block into the list
            prevAddress = readIntFromByteArray(memoryArray, address+8);
            nextAddress = readIntFromByteArray(memoryArray, address+12);
            if(prevAddress != 0) {
                writeIntToByteArray(memoryArray, nextAddress, prevAddress+12);
            }
            else list.head = nextAddress;

            if(nextAddress !=0) {
                writeIntToByteArray(memoryArray, prevAddress, nextAddress+8);
            }
            else list.tail = prevAddress;
            return (address+8);
        }

        else if(list != null) {
            // Take the first item from the fixed size list
            int nextPtr = readIntFromByteArray(memoryArray, address+12);
            int prevPtr = readIntFromByteArray(memoryArray, address+8);

            if(list.tail == list.head) list.tail = nextPtr; 
            list.head = nextPtr; 

            if(nextPtr != 0) {
                //Prev pointer of next block set to null
                writeIntToByteArray(memoryArray, 0, nextPtr+8);
            }

            // Add lenght and 'used' flag metadata
            writeBooleanAndIntToByteArray(memoryArray, true, chunkSize+8, address+8+chunkSize);
        }
        return (address+8);
    }

    // Finds best fitting fixed size list (if no list matches, chooses variableLengthList)
    public FreeList findBestFit(int requestedSize) {
        for (int i = 0; i < lists.length; i++) {
            if (lists[i].chunkSize == requestedSize && lists[i] != null) {
                return lists[i];
            }
        }
        return variableLengthList;
    }

    public FreeList chooseList(int blockLength){
        FreeList list = variableLengthList;
        if(blockLength == 24) list = list16;
        else if(blockLength == 40) list = list32;
        else if(blockLength == 72) list = list64;
        else if(blockLength == 136) list = list128;
        else if(blockLength == 264) list = list256;
        else if(blockLength == 520) list = list512;
        return list;
    }

    // Object to hold basic properties of a free list
    class FreeList{
        Integer head;
        Integer tail;
        int chunkSize;

        public FreeList(Integer head, Integer tail, int chunkSize){
            this.head = head;
            this.tail = tail;
            this.chunkSize = chunkSize;
        }
    }

    // Object to hold properties of n sbrk() array
    class SbrkReturnType {
        Integer pointer;
        byte array[];
        int size;
        
        public SbrkReturnType(Integer pointer, byte array[], int size){
            this.pointer = pointer;
            this.array = array;
            this.size = size;
        }
    }

    // Calculating array size for sbrk
    private int calculateChunkSize(int size) {
        int chunkSize = 1;
        while (chunkSize < size) {
            chunkSize *= 2;
        }
        return chunkSize;
    }

    // Generates new starting addrss for the sbrk array
    public int calculateNewSbrkAddress(int requestedSize) {
        Random random = new Random();
        int newAddress = 8192;

        for (SbrkReturnType allocation : allocations) {
            int endAddress = allocation.pointer + allocation.size;
            if (newAddress < endAddress) {
                newAddress = endAddress; 
            }
        }
        int offset = random.nextInt(256);
        newAddress += offset;

        return newAddress;
    }

    // Looks for available space in the free list specifically for memory allocated by calling sbrk()
    public int searchSbrkForFreeSpace(int size){
        int temp = sbrkFreeList.head;
        int thisSize = 0; 
        boolean restartLoop;
        while(temp != 0) {
            restartLoop = false;
        
            for (SbrkReturnType allocation : allocations) {
                if(temp >= allocation.pointer && temp <= (allocation.pointer + allocation.size) && allocation.size >= size + 16) {
                    
                    while(temp < allocation.pointer + allocation.size && temp >= allocation.pointer) {
                        thisSize = readIntFromByteArray(allocation.array, temp + 4 - allocation.pointer);

                        if(thisSize >= size + 8){
                            return temp;
                        }
                        temp = readIntFromByteArray(allocation.array, temp + 12 - allocation.pointer);
                    }
                    // if the while loop condition fails, restart the for loop
                    restartLoop = true;
                    break;
                }
            }
            if (!restartLoop) {
                break; 
            }
        }
        return 0;
    }

     // Allocates a used memory chunk of a given size to the given address to the sbrkFreeList
    public void allocatetoSbrkFreeList(int size, int sbrkFreeSpace){

        SbrkReturnType alloc = null;
        SbrkReturnType nextAlloc = null;
        SbrkReturnType prevAlloc = null;

        // Find the sbrk array that the free space address
        for (SbrkReturnType allocation : allocations) {
            if(sbrkFreeSpace >= allocation.pointer && sbrkFreeSpace <= (allocation.pointer+allocation.size+8)) {
                alloc = allocation;
            }
        }
        // Get metadata from the free block we are about to use
        int thisSize = readIntFromByteArray(alloc.array, sbrkFreeSpace-alloc.pointer+4);
        int prevPtr = readIntFromByteArray(alloc.array, sbrkFreeSpace-alloc.pointer+8);
        int nextPtr = readIntFromByteArray(alloc.array, sbrkFreeSpace-alloc.pointer+12);

        //Check which sbrk() arrays do previous and next blocks in the list belong to
        if(prevPtr != 0){
            for (SbrkReturnType allocation : allocations) {
                if(prevPtr >= allocation.pointer && prevPtr <= (allocation.pointer+allocation.size)) {
                    prevAlloc = allocation;
                }
            }
            // Edit next pointer of the previous block
            writeIntToByteArray(prevAlloc.array, nextPtr, prevPtr+12-prevAlloc.pointer);
        } else sbrkFreeList.head = nextPtr;

        if(nextPtr != 0){
            for (SbrkReturnType allocation : allocations) {
                if(nextPtr >= allocation.pointer && nextPtr <= (allocation.pointer+allocation.size)) {
                    nextAlloc = allocation;
                }
            }
            // Edit previous pointer of the next block
            writeIntToByteArray(nextAlloc.array, prevPtr, nextPtr+8-nextAlloc.pointer);
        } else sbrkFreeList.tail = prevPtr;

        // Size and 'used' flag metadata
        writeBooleanAndIntToByteArray(alloc.array, true, size+8, sbrkFreeSpace-alloc.pointer+4);

        // Checks whther the available space can be split in two parts, if the part we require is small enough
        if(thisSize>=size+24){
            // Splitting the block in two and creating new free block
            writeBooleanAndIntToByteArray(alloc.array, true, size+8, sbrkFreeSpace-alloc.pointer+8+size);
            writeBooleanAndIntToByteArray(alloc.array, false, thisSize-size-8, sbrkFreeSpace-alloc.pointer+12+size);
            writeIntToByteArray(alloc.array, prevPtr, sbrkFreeSpace-alloc.pointer+16+size);
            writeIntToByteArray(alloc.array, nextPtr, sbrkFreeSpace-alloc.pointer+20+size);
            writeBooleanAndIntToByteArray(alloc.array, false, thisSize-size-8, sbrkFreeSpace-alloc.pointer+thisSize-4);

            // Edit pointers of previous and next blocks to fit the newly formed block in the free list
            if(prevPtr !=0){
                writeIntToByteArray(prevAlloc.array, sbrkFreeSpace+8+size, prevPtr+12-prevAlloc.pointer);
            } else sbrkFreeList.head = sbrkFreeSpace+8+size;
            if(nextPtr !=0){
                writeIntToByteArray(nextAlloc.array, sbrkFreeSpace+8+size, nextPtr+8-nextAlloc.pointer);
            } else sbrkFreeList.tail = sbrkFreeSpace+8+size;
        } else {
            //Size and 'used' flag metadata at the end of the block in case we didn't manage to split it
            writeBooleanAndIntToByteArray(alloc.array, true, size+8, sbrkFreeSpace-alloc.pointer+thisSize-8);
        }
    }

    // Merges a block at the given address with the free block on its left in sbrk arrray
    public void coalesceSbrkLeft(SbrkReturnType allocation, int address, int prevLength){
        System.out.print(" coalescing... ");
        
        // Get length of current block
        int thisLen = readIntFromByteArray(allocation.array, address-4-allocation.pointer);

        // Edit current block's length and 'used' flag
        writeBooleanAndIntToByteArray(allocation.array, false, prevLength+thisLen, address+thisLen-8-allocation.pointer);
        // Edit left block's length 
        writeBooleanAndIntToByteArray(allocation.array, false, prevLength+thisLen, address-prevLength-4-allocation.pointer);

        System.out.print("memory freed.\n");
    }
    // Merges a block at the given address with the free block on its right in sbrk arrray
    public void coalesceSbrkRight(SbrkReturnType allocation, int address, int nextLength){
        SbrkReturnType nextAllocation = null;
        SbrkReturnType prevAllocation = null;
        System.out.print(" coalescing... ");;

        // Get length of current block
        int thisLen = readIntFromByteArray(allocation.array, address-4-allocation.pointer);

        // create free block metadata for current block
        writeBooleanAndIntToByteArray(allocation.array, false, nextLength+thisLen, address-4-allocation.pointer);
        writeIntToByteArray(allocation.array, 0, address-allocation.pointer);
        writeIntToByteArray(allocation.array, 0, address+4-allocation.pointer);

        int rightBlockAddress = address+thisLen-8-allocation.pointer;
        // get right block's next pointer
        int nextBlockAddress = readIntFromByteArray(allocation.array, rightBlockAddress+12);
        // get right block's prev pointer
        int prevBlockAddress = readIntFromByteArray(allocation.array, rightBlockAddress+8);
        // Edit length
        writeBooleanAndIntToByteArray(memoryArray, false, thisLen+nextLength, address+thisLen+nextLength-8-allocation.pointer);
        // Move current block's prev pointer to the previous block
        writeIntToByteArray(memoryArray, prevBlockAddress, address-allocation.pointer);
        // Move current block's next pointer to the next block
        writeIntToByteArray(memoryArray, nextBlockAddress, address+4-allocation.pointer);

        // Check which sbrk arrays do previous and next blocks belong to and edit their next and prev pointers accordingly
        if(prevBlockAddress != 0){
            for (SbrkReturnType item : allocations) {
                if(prevBlockAddress >= item.pointer && prevBlockAddress <= (item.pointer+item.size+8)) {
                    prevAllocation = item;
                }
            }
            // Move previous block's next pointer to this block
            writeIntToByteArray(prevAllocation.array, address-8, prevBlockAddress+12-prevAllocation.pointer);
        }
        //Move next block's prev pointer to this block
        if(nextBlockAddress != 0){
            for (SbrkReturnType item : allocations) {
                if(nextBlockAddress >= item.pointer && nextBlockAddress <= (item.pointer+item.size+8)) {
                    nextAllocation = item;
                }
            }
            writeIntToByteArray(nextAllocation.array, address-8, nextBlockAddress+8-nextAllocation.pointer);
        } 

        if(sbrkFreeList.head == rightBlockAddress+allocation.pointer) sbrkFreeList.head = address-8;
        if(sbrkFreeList.tail == rightBlockAddress+allocation.pointer) sbrkFreeList.tail = address-8;

        System.out.print("memory freed.\n");
    }

    /*
     * Next 4 methods adapted from a Stack Overflow page
     * Reference: [Stack Overflow - Convert a byte array to integer in Java and vice versa]
     * URL: https://stackoverflow.com/questions/7619058/convert-a-byte-array-to-integer-in-java-and-vice-versa
     */

    // The functiom writes an integer of the given value to 4 bytes in the given byte array starting with the given index
    public void writeIntToByteArray(byte[] array, int value, int index) {
        if (index < 0 || index + 3 >= array.length) {
            System.out.println("Index out of bounds");
        }
 
        array[index]     = (byte) (value >> 24);
        array[index + 1] = (byte) (value >> 16);
        array[index + 2] = (byte) (value >> 8);
        array[index + 3] = (byte) (value);
    }

    // I'm using this method for reading all integers from the array of memory assuming there won't be a big enough number to take up all available 32 bits
    // This method only reads 31 rightmost bits of what is stored in 4 bytes
    public int readIntFromByteArray(byte[] array, int index) {
        if (index < 0 || index + 3 >= array.length) {
            System.out.println("Index out of bounds");
        }
    
        int value = ((array[index] & 0xFF) << 24)
                  | ((array[index + 1] & 0xFF) << 16)
                  | ((array[index + 2] & 0xFF) << 8)
                  | (array[index + 3] & 0xFF);
    
        // clearing the left most bit ('used' flag value is stored there)
        return value & 0x7FFFFFFF;
    }
    
    // The function reads the leftmost bit in the given byte array at the given index 
    public boolean readBooleanFromByteArray(byte[] array, int index) {
        if (index < 0 || index + 3 >= array.length) {
            System.out.println("Index out of bounds");
        }
    
        return (array[index] & 0x80) != 0;
    }

    // The functiom writes an integer of the given value and the given boolean to 4 bytes in the given byte array starting with the given index
    // It uses leftmost bit for the flag and the remaining 31 bits for the int value
    public void writeBooleanAndIntToByteArray(byte[] array, boolean flag, int value, int index) {
        if (index < 0 || index + 3 >= array.length) {
            System.out.println("Index out of bounds");
        }
    
        if ((value >>> 31) != 0) {
            System.out.println("Integer too large");
            return;
        }
    
        // set the leftmost bit based on the flag
        value = flag ? (value | 0x80000000) : (value & 0x7FFFFFFF);
    
        array[index]     = (byte) (value >> 24);
        array[index + 1] = (byte) (value >> 16);
        array[index + 2] = (byte) (value >> 8);
        array[index + 3] = (byte) (value);
    }

    /*
     * Next 2 methods adapted from an external resource
     * Reference: [Digital Ocean - String to byte array, byte array to String in Java]
     * URL: https://www.digitalocean.com/community/tutorials/string-byte-array-java
     */

    // Method to write string to the chosen position in the byte array
    public void writeStringToByteArray(byte[] array, String str, int index) {
        byte[] stringBytes;

        System.out.println("\nWriting string to address: "+index+ "...");

        // append the '\0' character to the string
        stringBytes = (str + "\0").getBytes(StandardCharsets.UTF_8);
  
        System.arraycopy(stringBytes, 0, array, index, stringBytes.length);
    }
    
    // Method to read a string from the chosen position in a byte array
    public String readStringFromByteArray(byte[] array, int index) {
        int end = index;

        System.out.println("Reading string from address: "+index+"...");
        // find where the string ends
        while (end < array.length && array[end] != 0) {
            end++;
        }
   
        return new String(array, index, end, StandardCharsets.UTF_8);
    }
}