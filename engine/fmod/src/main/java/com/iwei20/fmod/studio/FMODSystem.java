package com.iwei20.fmod.studio;

import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.C_INT;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.C_LONG;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.C_POINTER;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_INIT_NORMAL;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_STUDIO_INIT_NORMAL;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_STUDIO_LOAD_BANK_NORMAL;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_STUDIO_STOP_ALLOWFADEOUT;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_STUDIO_STOP_IMMEDIATE;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_Bank_GetEventCount;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_Bank_GetEventList;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_Bank_LoadSampleData;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_Bank_Unload;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_EventDescription_CreateInstance;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_EventDescription_GetID;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_EventDescription_GetPath;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_EventInstance_GetPlaybackState;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_EventInstance_Release;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_EventInstance_Set3DAttributes;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_EventInstance_Start;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_EventInstance_Stop;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_System_Create;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_System_GetEvent;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_System_Initialize;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_System_LoadBankFile;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_System_Release;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_System_SetListenerAttributes;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_Studio_System_Update;
import static com.iwei20.fmod.gen.fmodstudio.fmod_studio_h.FMOD_VERSION;

import com.iwei20.fmod.gen.fmodstudio.FMOD_3D_ATTRIBUTES;
import com.iwei20.fmod.gen.fmodstudio.FMOD_FILE_CLOSE_CALLBACK;
import com.iwei20.fmod.gen.fmodstudio.FMOD_FILE_OPEN_CALLBACK;
import com.iwei20.fmod.gen.fmodstudio.FMOD_FILE_READ_CALLBACK;
import com.iwei20.fmod.gen.fmodstudio.FMOD_FILE_SEEK_CALLBACK;
import com.iwei20.fmod.gen.fmodstudio.FMOD_GUID;
import com.iwei20.fmod.gen.fmodstudio.FMOD_STUDIO_ADVANCEDSETTINGS;
import com.iwei20.fmod.gen.fmodstudio.FMOD_STUDIO_BANK_INFO;
import com.iwei20.fmod.gen.fmodstudio.FMOD_STUDIO_BUFFER_INFO;
import com.iwei20.fmod.gen.fmodstudio.FMOD_VECTOR;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FMODSystem implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment systemPointer;

    /**
     * Calls Studio::System::create.
     *
     * {@link FMODSystem#FMODSystem} and {@link FMODSystem#close} are not thread-safe. Calling
     * either of these functions concurrently with any Studio API function (including
     * these two functions) may cause undefined behavior. External synchronization
     * must be used if calls to {@link FMODSystem#FMODSystem} or {@link FMODSystem#close} could
     * overlap other Studio API calls. All other Studio API functions are thread safe
     * and may be called freely from any thread unless otherwise documented.
     *
     * @throws FMODException if the call the Studio::System::create fails.
     * @see https://www.fmod.com/docs/2.03/api/studio-guide.html#creating-the-studio-system
     */
    public FMODSystem() throws FMODException {
        arena = Arena.ofAuto();

        MemorySegment systemPointerPointer = arena.allocate(C_POINTER);
        int result = FMOD_Studio_System_Create(systemPointerPointer, FMOD_VERSION());
        FMODException.errCheck(result);

        systemPointer = systemPointerPointer.get(C_POINTER, 0);
    }

    /**
     * This function will free the memory used by the Studio System object and everything
     * created under it.
     *
     * {@link FMODSystem#FMODSystem} and {@link FMODSystem#close} are not thread-safe. Calling
     * either of these functions concurrently with any Studio API function (including
     * these two functions) may cause undefined behavior. External synchronization
     * must be used if calls to {@link FMODSystem#FMODSystem} or {@link FMODSystem#close} could
     * overlap other Studio API calls. All other Studio API functions are thread safe
     * and may be called freely from any thread unless otherwise documented.
     *
     * @throws FMODException if the call to Studio::System::release fails.
     * @see https://www.fmod.com/docs/2.03/api/studio-guide.html#creating-the-studio-system
     */
    @Override
    public void close() throws FMODException {
        int result = FMOD_Studio_System_Release(systemPointer);
        FMODException.errCheck(result);
    }

    /**
     * When calling {@link FMODSystem#setAdvancedSettings}, any int member may
     * be set to zero, and any Optional member may be set empty, to use the default value
     * for that setting.
     *
     * @param commandQueueSize Command queue size for studio async processing. Units: Bytes. Default: 32768.
     * @param handleInitialSize Initial size to allocate for handles. Memory for handles will grow as needed in pages. Units: Bytes. Default: 8192 * sizeof(void*).
     * @param studioUpdatePeriod Update period of Studio when in async mode, in milliseconds. Will be quantized to the nearest multiple of mixer duration. Units: Milliseconds Default: 20.
     * @param idleSampleDatapoolSize Size in bytes of sample data to retain in memory when no longer used, to avoid repeated disk I/O. Use -1 to disable. Units: Bytes. Default: 262144.
     * @param streamingScheduleDelay Specify the schedule delay for streams, in samples. Lower values can reduce latency when scheduling events containing streams but may cause scheduling issues if too small. Units: Samples. Default: 8192.
     * @param encryptionKey Specify the key for loading sounds from encrypted banks. (UTF-8 string)
     */
    public static record AdvancedSettings(
            int commandQueueSize,
            int handleInitialSize,
            int studioUpdatePeriod,
            int idleSampleDatapoolSize,
            int streamingScheduleDelay,
            Optional<String> encryptionKey) {

        /**f
         * Constructs an AdvancedSettings objects from an FMOD_STUDIO_ADVANCEDSETTINGS
         * native struct.
         *
         * @param advancedSettingsPointer A memory segment pointing to a FMOD_STUDIO_ADVANCEDSETTINGS native struct.
         * @return A corresponding AdvancedSettings object.
         */
        public static AdvancedSettings fromNative(MemorySegment advancedSettingsPointer) {
            MemorySegment encryptionKeyPointer = FMOD_STUDIO_ADVANCEDSETTINGS.encryptionkey(advancedSettingsPointer);
            Optional<String> encryptionKey;
            if (encryptionKeyPointer.address() == 0) {
                encryptionKey = Optional.empty();
            } else {
                encryptionKey = Optional.of(encryptionKeyPointer.getString(0));
            }

            return new AdvancedSettings(
                FMOD_STUDIO_ADVANCEDSETTINGS.commandqueuesize(advancedSettingsPointer),
                FMOD_STUDIO_ADVANCEDSETTINGS.handleinitialsize(advancedSettingsPointer),
                FMOD_STUDIO_ADVANCEDSETTINGS.studioupdateperiod(advancedSettingsPointer),
                FMOD_STUDIO_ADVANCEDSETTINGS.idlesampledatapoolsize(advancedSettingsPointer),
                FMOD_STUDIO_ADVANCEDSETTINGS.streamingscheduledelay(advancedSettingsPointer),
                encryptionKey
            );
        }

        /**
         * Allocates a FMOD_STUDIO_ADVANCEDSETTINGS native struct corresponding
         * to the advanced settings set in this record.
         *
         * @param allocator The allocator used to allocate the struct
         * @return A memory segment pointing to the allocated struct
         */
        public MemorySegment allocate(SegmentAllocator allocator) {
            MemorySegment advancedSettingsPointer = FMOD_STUDIO_ADVANCEDSETTINGS.allocate(allocator);

            // Since there is no clear return type for sizeof anyway, this cast is OK.
            FMOD_STUDIO_ADVANCEDSETTINGS.cbsize(advancedSettingsPointer, (int) FMOD_STUDIO_ADVANCEDSETTINGS.sizeof());
            FMOD_STUDIO_ADVANCEDSETTINGS.commandqueuesize(advancedSettingsPointer, commandQueueSize);
            FMOD_STUDIO_ADVANCEDSETTINGS.handleinitialsize(advancedSettingsPointer, handleInitialSize);
            FMOD_STUDIO_ADVANCEDSETTINGS.studioupdateperiod(advancedSettingsPointer, studioUpdatePeriod);
            FMOD_STUDIO_ADVANCEDSETTINGS.idlesampledatapoolsize(advancedSettingsPointer, idleSampleDatapoolSize);
            FMOD_STUDIO_ADVANCEDSETTINGS.streamingscheduledelay(advancedSettingsPointer, streamingScheduleDelay);

            MemorySegment encryptionKeyPointer = encryptionKey
                    .map((String key) -> allocator.allocateFrom(key))
                    .orElse(MemorySegment.NULL);
            FMOD_STUDIO_ADVANCEDSETTINGS.encryptionkey(advancedSettingsPointer, encryptionKeyPointer);

            return advancedSettingsPointer;
        }
    }

    /**
     * Callback for opening a file.
     *
     * Return the appropriate error code such as {@link FMODResult#FMOD_ERR_FILE_NOTFOUND}
     * if the file fails to open. If the callback is from {@link FMODSystem#attachFileSystem},
     * then the return value is ignored.
     *
     * @see FMODSystem#setFileSystem
     * @see FileCloseCallback
     * @see FileReadCallback
     * @see FileSeekCallback
     * @see FileAsyncReadCallback
     * @see FileAsyncCancelCallback
     *
     * <b>Experimental.</b> This API is subject to change if
     * I find a better way to represent file handles.
     */
    public static interface FileOpenCallback extends FMOD_FILE_OPEN_CALLBACK.Function {
        /**
         * Outputs of the callback.
         *
         * @param fileSize Size of the file. Units: Bytes.
         * @param handle File handle to identify this file in future file callbacks.
         * @param errCode Result of the callback.
         */
        public static record Out(int fileSize, MemorySegment handle, FMODResult errCode) {}

        @Override
        default int apply(MemorySegment name, MemorySegment filesize, MemorySegment handle, MemorySegment userdata) {
            String strName = name.getString(0);
            Out result = call(strName, userdata);
            filesize.set(C_INT, 0, result.fileSize());
            handle.set(C_POINTER, 0, result.handle()); // Double pointer, as opposed to the result's single pointer
            return result.errCode().code();
        }

        /**
         * Callback for opening a file.
         *
         * <b>Experimental.</b> This API is subject to change if
         * I find a better way to represent file handles.
         *
         * @param name File name or identifier. (UTF-8 string)
         * @param userData User value set by {@link CreateSoundEXInfo#fileUserData} or {@link BankInfo#userData}.
         * @return See the {@link Out} record's documentation for return information.
         */
        public Out call(String name, MemorySegment userData);
    }

    /**
     * Callback for closing a file.
     *
     * Close any user created file handle and perform any cleanup necessary for the file here.
     * If the callback is from {@link FMODSystem#attachFileSystem}, then the return value is ignored.
     *
     * @see FMODSystem#setFileSystem
     * @see FileOpenCallback
     * @see FileReadCallback
     * @see FileSeekCallback
     * @see FileAsyncReadCallback
     * @see FileAsyncCancelCallback
     *
     * <b>Experimental.</b> This API is subject to change if
     * I find a better way to represent file handles.
     */
    public static interface FileCloseCallback extends FMOD_FILE_CLOSE_CALLBACK.Function {
        @Override
        default int apply(MemorySegment handle, MemorySegment userdata) {
            FMODResult result = call(handle, userdata);
            return result.code();
        }

        /**
         * Callback for closing a file.
         *
         * <b>Experimental.</b> This API is subject to change if
         * I find a better way to represent file handles.
         *
         * @param handle File handle that was returned in {@link FileOpenCallback#call}.
         * @param userData User value set by {@link CreateSoundEXInfo#fileUserData} or {@link BankInfo#userData}.
         * @return Result of the callback.
         */
        public FMODResult call(MemorySegment handle, MemorySegment userData);
    }

    /**
     * Callback for reading from a file.
     *
     * If the callback is from {@link FMODSystem#attachFileSystem}, then the return value is ignored.
     *
     * If there is not enough data to read the requested number of bytes, return fewer bytes in the
     * bytesread parameter and and return {@link FMODResult#FMOD_ERR_FILE_EOF}.
     *
     * @see FMODSystem#setFileSystem
     * @see FileOpenCallback
     * @see FileCloseCallback
     * @see FileSeekCallback
     * @see FileAsyncReadCallback
     * @see FileAsyncCancelCallback
     *
     * <b>Experimental.</b> This API is subject to change if
     * I find a better way to represent file handles.
     */
    public static interface FileReadCallback extends FMOD_FILE_READ_CALLBACK.Function {
        /**
         * Outputs of the callback.
         *
         * @param bytesRead Number of bytes read into buffer.
         * @param errCode Result of the callback.
         */
        public static record Out(int bytesRead, FMODResult errCode) {}

        @Override
        default int apply(
                MemorySegment handle,
                MemorySegment buffer,
                int sizebytes,
                MemorySegment bytesread,
                MemorySegment userdata) {
            Out result = call(handle, sizebytes, userdata, buffer);
            bytesread.set(C_INT, 0, result.bytesRead());
            return result.errCode().code();
        }

        /**
         * Callback for reading from a file.
         *
         * <b>Experimental.</b> This API is subject to change if
         * I find a better way to represent file handles.
         *
         * @param handle File handle that was returned in {@link FileOpenCallback#call}.
         * @param sizeBytes Number of bytes to read into buffer. Units: Bytes.
         * @param userData User value set by {@link CreateSoundEXInfo#fileUserData} or {@link BankInfo#userData}.
         * @param buffer Output for you to mutate! Buffer to read data into.
         * @return Mutates buffer. See the {@link Out} record's documentation for return information.
         */
        public Out call(MemorySegment handle, int sizeBytes, MemorySegment userData, MemorySegment buffer);
    }

    /**
     * Callback for seeking within a file.
     *
     * If the callback is from {@link FMODSystem#attachFileSystem}, then the return value is ignored.
     *
     * @see FMODSystem#setFileSystem
     * @see FileOpenCallback
     * @see FileCloseCallback
     * @see FileReadCallback
     * @see FileAsyncReadCallback
     * @see FileAsyncCancelCallback
     *
     * <b>Experimental.</b> This API is subject to change if
     * I find a better way to represent file handles.
     */
    public static interface FileSeekCallback extends FMOD_FILE_SEEK_CALLBACK.Function {
        @Override
        default int apply(MemorySegment handle, int pos, MemorySegment userdata) {
            FMODResult result = call(handle, pos, userdata);
            return result.code();
        }

        /**
         * Callback for seeking within a file.
         *
         * @param handle File handle that returned in {@link FileOpenCallback}
         * @param pos Absolute position to seek to in file. Units: Bytes.
         * @param userData User value set by {@link CreateSoundEXInfo#fileUserData} or {@link BankInfo#userData}.
         * @return Result of the callback.
         */
        public FMODResult call(MemorySegment handle, int pos, MemorySegment userData);
    }

    /**
     * Information for loading a bank using user callbacks.
     *
     * @param userData (optional) data to be passed to the file callbacks.
     * If {@link BankInfo#userDataLength} is zero, this must remain valid until the bank
     * has been unloaded and all calls to {@link BankInfo#openCallback} have been matched by
     * a call to {@link BankInfo#closeCallback}.
     * @param userDataLength Length of user data in bytes. If non-zero the {@link BankInfo#userData}
     * will be copied internally; this copy will be kept until the bank has been unloaded and all
     * calls to {@link BankInfo#openCallback} have been matched by a call to {@link BankInfo#closeCallback}.
     * @param openCallback Callback for opening the bank file.
     * @param closeCallback Callback for closing the bank file.
     * @param readCallback Callback for reading from the bank file.
     * @param seekCallback Callback for seeking within the bank file.
     * @see FMODSystem#loadBankCustom
     */
    public static record BankInfo(
            MemorySegment userData,
            int userDataLength,
            FileOpenCallback openCallback,
            FileCloseCallback closeCallback,
            FileReadCallback readCallback,
            FileSeekCallback seekCallback) {

        /**
         * Allocates a FMOD_STUDIO_ADVANCEDSETTINGS native struct corresponding
         * to the advanced settings set in this record.
         *
         * @param allocator The allocator used to allocate the struct
         * @return A memory segment pointing to the allocated struct
         */
        public MemorySegment allocate(Arena arena) {
            MemorySegment bankInfoPointer = FMOD_STUDIO_BANK_INFO.allocate(arena);

            FMOD_STUDIO_BANK_INFO.size(bankInfoPointer, (int) FMOD_STUDIO_BANK_INFO.sizeof());
            FMOD_STUDIO_BANK_INFO.userdata(bankInfoPointer, userData);
            FMOD_STUDIO_BANK_INFO.userdatalength(bankInfoPointer, userDataLength);
            FMOD_STUDIO_BANK_INFO.opencallback(bankInfoPointer, FMOD_FILE_OPEN_CALLBACK.allocate(openCallback, arena));
            FMOD_STUDIO_BANK_INFO.closecallback(
                    bankInfoPointer, FMOD_FILE_CLOSE_CALLBACK.allocate(closeCallback, arena));
            FMOD_STUDIO_BANK_INFO.readcallback(bankInfoPointer, FMOD_FILE_READ_CALLBACK.allocate(readCallback, arena));
            FMOD_STUDIO_BANK_INFO.seekcallback(bankInfoPointer, FMOD_FILE_SEEK_CALLBACK.allocate(seekCallback, arena));

            return bankInfoPointer;
        }
    }

    /**
     * Information for a single buffer in FMOD Studio.
     * 
     * @param currentUsage Current buffer usage in bytes.
     * @param peakUsage Peak buffer usage in bytes.
     * @param capacityBuffer capacity in bytes.
     * @param stallCount Cumulative number of stalls due to buffer overflow. 
     * @param stallTime Cumulative amount of time stalled due to buffer overflow, in seconds.
     * @see BufferUsage
     */
    public static record BufferInfo(
            int currentUsage,
            int peakUsage,
            int capacity,
            int stallCount,
            float stallTime) {

        /**
         * Allocates a FMOD_STUDIO_BUFFER_INFO native struct corresponding
         * to the advanced settings set in this record.
         *
         * @param allocator The allocator used to allocate the struct
         * @return A memory segment pointing to the allocated struct
         */
        public MemorySegment allocate(SegmentAllocator allocator) {
            MemorySegment bufferInfoPointer = FMOD_STUDIO_BUFFER_INFO.allocate(allocator);

            FMOD_STUDIO_BUFFER_INFO.currentusage(bufferInfoPointer, currentUsage);
            FMOD_STUDIO_BUFFER_INFO.peakusage(bufferInfoPointer, peakUsage);
            FMOD_STUDIO_BUFFER_INFO.capacity(bufferInfoPointer, capacity);
            FMOD_STUDIO_BUFFER_INFO.stallcount(bufferInfoPointer, stallCount);
            FMOD_STUDIO_BUFFER_INFO.stalltime(bufferInfoPointer, stallTime);

            return bufferInfoPointer;
        }
    }

    /**
     * <ul>
     * <li>InitNormal - Use defaults for all initialization options.
     * <li>LiveUpdate - Enable live update.
     * <li>AllowMissingPlugins - Load banks even if they reference plug-ins
     * that have not been loaded.
     * <li>SynchronousUpdate - Disable asynchronous processing and perform
     * all processing on the calling thread instead.
     * <li>DeferredCallbacks - Defer timeline callbacks until the main update.
     * See EventInstance#setCallback for more information.
     * <li>LoadFromUpdate - No additional threads are created for bank and
     * resource loading. Loading is driven from System#update.
     * <li>MemoryTracking - Enables detailed memory usage statistics.
     * Increases memory footprint and impacts performance. See {@link Bus#getMemoryUsage}
     * and {@link EventInstance#getMemoryUsage} for more information. Implies
     * InitFlags.MemoryTracking.
     * </ul>
     *
     * @see FMODSystem#initialize
     */
    public static enum InitFlags {
        Normal(0x00000000),
        LiveUpdate(0x00000001),
        AllowMissingPlugins(0x00000002),
        SynchronousUpdate(0x00000004),
        DeferredCallbacks(0x00000008),
        LoadFromUpdate(0x00000010),
        MemoryTracking(0x00000020);

        private final int constant;

        private InitFlags(int constant) {
            this.constant = constant;
        }

        public int getConstant() {
            return constant;
        }
    }

    /**
     * Calls Studio::System::initialize with the given options.
     *
     * @param maxChannels The maximum number of channels to be used in FMOD.
     * @param studioFlags Flags, built from {@link InitFlags}, to control initialization behavior.
     * @param flags Flags, from the core FMOD_INIT_* constants, passed through to the core System.
     * @throws FMODException if the call to Studio::System::initialize fails.
     * @see https://www.fmod.com/docs/2.03/api/studio-guide.html#creating-the-studio-system
     */
    public void initialize(int maxChannels, int studioFlags, int flags) {
        FMODException.errCheck(
                FMOD_Studio_System_Initialize(systemPointer, maxChannels, studioFlags, flags, MemorySegment.NULL));
    }

    /**
     * Calls Studio::System::initialize with reasonable defaults: 1024 channels, no special
     * studio or core init flags.
     *
     * @throws FMODException if the call to Studio::System::initialize fails.
     */
    public void initialize() {
        initialize(1024, FMOD_STUDIO_INIT_NORMAL(), FMOD_INIT_NORMAL());
    }

    /**
     * Calls Studio::System::initialize with 1024 channels, no special core init flags, and
     * the given studio flags (built from {@link InitFlags}).
     *
     * @param studioFlags Flags, built from {@link InitFlags}, to control initialization behavior.
     * @throws FMODException if the call to Studio::System::initialize fails.
     */
    public void initialize(int studioFlags) {
        initialize(1024, studioFlags, FMOD_INIT_NORMAL());
    }

    /**
     * Calls Studio::System::update. Must be called regularly (e.g. once per frame) for
     * scheduled sounds, streaming, callbacks, and file loading to progress.
     *
     * @throws FMODException if the call to Studio::System::update fails.
     */
    public void update() {
        FMODException.errCheck(FMOD_Studio_System_Update(systemPointer));
    }

    /**
     * Loads the bank at the given file path.
     *
     * @param filename Path to the bank file to load.
     * @param flags Flags, built from the FMOD_STUDIO_LOAD_BANK_* constants.
     * @return The loaded {@link Bank}.
     * @throws FMODException if the call to Studio::System::loadBankFile fails.
     */
    public Bank loadBankFile(String filename, int flags) {
        MemorySegment filenameSegment = arena.allocateFrom(filename);
        MemorySegment bankPointerPointer = arena.allocate(C_POINTER);

        FMODException.errCheck(
                FMOD_Studio_System_LoadBankFile(systemPointer, filenameSegment, flags, bankPointerPointer));

        return new Bank(bankPointerPointer.get(C_POINTER, 0));
    }

    /**
     * Loads the bank at the given file path with default flags.
     *
     * @param filename Path to the bank file to load.
     * @return The loaded {@link Bank}.
     * @throws FMODException if the call to Studio::System::loadBankFile fails.
     */
    public Bank loadBankFile(String filename) {
        return loadBankFile(filename, FMOD_STUDIO_LOAD_BANK_NORMAL());
    }

    /**
     * Calls Studio::System::getEvent to retrieve an event description by path or ID string
     * (e.g. {@code "event:/UI/Cancel"} or {@code "{guid}"}).
     *
     * @param pathOrId The path or ID string identifying the event.
     * @return The {@link EventDescription} for the given path or ID.
     * @throws FMODException if the call to Studio::System::getEvent fails.
     */
    public EventDescription getEvent(String pathOrId) {
        MemorySegment pathSegment = arena.allocateFrom(pathOrId);
        MemorySegment eventPointerPointer = arena.allocate(C_POINTER);

        FMODException.errCheck(FMOD_Studio_System_GetEvent(systemPointer, pathSegment, eventPointerPointer));

        return new EventDescription(eventPointerPointer.get(C_POINTER, 0));
    }

    /**
     * A 3D vector, as used by {@link Attributes3D}.
     */
    public static record Vector3(float x, float y, float z) {
        public static final Vector3 ZERO = new Vector3(0, 0, 0);

        /**
         * Allocates a FMOD_VECTOR native struct corresponding to this vector.
         *
         * @param allocator The allocator used to allocate the struct
         * @return A memory segment pointing to the allocated struct
         */
        public MemorySegment allocate(SegmentAllocator allocator) {
            MemorySegment vectorPointer = FMOD_VECTOR.allocate(allocator);
            FMOD_VECTOR.x(vectorPointer, x);
            FMOD_VECTOR.y(vectorPointer, y);
            FMOD_VECTOR.z(vectorPointer, z);
            return vectorPointer;
        }
    }

    /**
     * 3D position, velocity, and orientation, as used by {@link FMODSystem#setListenerAttributes}
     * and {@link EventInstance#set3DAttributes}.
     */
    public static record Attributes3D(Vector3 position, Vector3 velocity, Vector3 forward, Vector3 up) {
        /**
         * A stationary point with an arbitrary (but valid) orientation and no velocity, for
         * sounds that only need positional attenuation/panning, not doppler or directionality.
         *
         * @param x X position. Units: Distance units, as defined by the core system's 3D
         *     settings (1.0 = 1 meter, by default).
         * @param y Y position.
         * @param z Z position.
         * @return The corresponding stationary {@link Attributes3D}.
         */
        public static Attributes3D stationary(float x, float y, float z) {
            return new Attributes3D(new Vector3(x, y, z), Vector3.ZERO, new Vector3(0, 0, 1), new Vector3(0, 1, 0));
        }

        /**
         * Allocates a FMOD_3D_ATTRIBUTES native struct corresponding to these attributes.
         *
         * @param arena The arena used to allocate the struct and its nested vectors
         * @return A memory segment pointing to the allocated struct
         */
        public MemorySegment allocate(Arena arena) {
            MemorySegment attributesPointer = FMOD_3D_ATTRIBUTES.allocate(arena);
            FMOD_3D_ATTRIBUTES.position(attributesPointer, position.allocate(arena));
            FMOD_3D_ATTRIBUTES.velocity(attributesPointer, velocity.allocate(arena));
            FMOD_3D_ATTRIBUTES.forward(attributesPointer, forward.allocate(arena));
            FMOD_3D_ATTRIBUTES.up(attributesPointer, up.allocate(arena));
            return attributesPointer;
        }
    }

    /**
     * Calls Studio::System::setListenerAttributes to set the position/orientation of the given
     * listener, used to pan and attenuate 3D event instances. Must be called at least once
     * (and updated regularly, e.g. once per frame, if the listener moves) for 3D events to be
     * audible with correct panning/attenuation.
     *
     * @param listenerIndex Index of the listener to set the attributes of.
     * @param attributes The listener's new position/velocity/orientation.
     * @throws FMODException if the call to Studio::System::setListenerAttributes fails.
     */
    public void setListenerAttributes(int listenerIndex, Attributes3D attributes) {
        Arena arena = Arena.ofConfined();
        try {
            MemorySegment attributesPointer = attributes.allocate(arena);
            FMODException.errCheck(FMOD_Studio_System_SetListenerAttributes(
                    systemPointer, listenerIndex, attributesPointer, MemorySegment.NULL));
        } finally {
            arena.close();
        }
    }

    /**
     * A loaded FMOD Studio bank, as returned by {@link FMODSystem#loadBankFile}.
     */
    public static class Bank {
        private final MemorySegment bankPointer;

        private Bank(MemorySegment bankPointer) {
            this.bankPointer = bankPointer;
        }

        /**
         * Calls Studio::Bank::loadSampleData to preload the sample data for all
         * events in this bank, so playback of those events starts without delay.
         *
         * @throws FMODException if the call to Studio::Bank::loadSampleData fails.
         */
        public void loadSampleData() {
            FMODException.errCheck(FMOD_Studio_Bank_LoadSampleData(bankPointer));
        }

        /**
         * Calls Studio::Bank::unload to unload this bank and all of its contained
         * resources (events, VCAs, buses).
         *
         * @throws FMODException if the call to Studio::Bank::unload fails.
         */
        public void unload() {
            FMODException.errCheck(FMOD_Studio_Bank_Unload(bankPointer));
        }

        /**
         * Calls Studio::Bank::getEventCount and Studio::Bank::getEventList, then
         * Studio::EventDescription::getPath on each result, to list the full paths
         * (e.g. {@code "event:/Weapons/Explosion"}) of every event contained in this bank.
         *
         * <p>Requires the project's strings bank (Master.strings.bank) to already be
         * loaded, since paths are resolved from it.
         *
         * @return The full path of every event in this bank.
         * @throws FMODException if any of the underlying Studio API calls fail.
         */
        public List<String> getEventPaths() {
            Arena arena = Arena.ofAuto();

            MemorySegment countPointer = arena.allocate(C_INT);
            FMODException.errCheck(FMOD_Studio_Bank_GetEventCount(bankPointer, countPointer));
            int count = countPointer.get(C_INT, 0);

            MemorySegment eventArray = arena.allocate(C_POINTER, count);
            MemorySegment retrievedPointer = arena.allocate(C_INT);
            FMODException.errCheck(FMOD_Studio_Bank_GetEventList(bankPointer, eventArray, count, retrievedPointer));

            List<String> paths = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                MemorySegment eventDescriptionPointer = eventArray.getAtIndex(C_POINTER, i);

                MemorySegment pathBuffer = arena.allocate(512);
                MemorySegment pathRetrievedPointer = arena.allocate(C_INT);
                FMODException.errCheck(FMOD_Studio_EventDescription_GetPath(
                        eventDescriptionPointer, pathBuffer, (int) pathBuffer.byteSize(), pathRetrievedPointer));

                paths.add(pathBuffer.getString(0));
            }

            return paths;
        }

        /**
         * Calls Studio::Bank::getEventCount and Studio::Bank::getEventList to retrieve
         * every event handle contained in this bank directly, without going through
         * {@link FMODSystem#getEvent} (path lookup) at all.
         *
         * <p>Unlike {@link Bank#getEventPaths}, this does <b>not</b> require the project's
         * strings bank (Master.strings.bank) to be loaded, since it never resolves a
         * path or GUID to a string.
         *
         * @return Every {@link EventDescription} contained in this bank.
         * @throws FMODException if any of the underlying Studio API calls fail.
         */
        public List<EventDescription> getEvents() {
            Arena arena = Arena.ofAuto();

            MemorySegment countPointer = arena.allocate(C_INT);
            FMODException.errCheck(FMOD_Studio_Bank_GetEventCount(bankPointer, countPointer));
            int count = countPointer.get(C_INT, 0);

            MemorySegment eventArray = arena.allocate(C_POINTER, count);
            MemorySegment retrievedPointer = arena.allocate(C_INT);
            FMODException.errCheck(FMOD_Studio_Bank_GetEventList(bankPointer, eventArray, count, retrievedPointer));

            List<EventDescription> events = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                events.add(new EventDescription(eventArray.getAtIndex(C_POINTER, i)));
            }

            return events;
        }
    }

    /**
     * A description of an FMOD Studio event, as returned by {@link FMODSystem#getEvent}.
     * Use {@link EventDescription#createInstance} to create playable instances of this event.
     */
    public static class EventDescription {
        private final MemorySegment eventDescriptionPointer;

        private EventDescription(MemorySegment eventDescriptionPointer) {
            this.eventDescriptionPointer = eventDescriptionPointer;
        }

        /**
         * Calls Studio::EventDescription::createInstance to create a playable instance
         * of this event.
         *
         * @return The created {@link EventInstance}.
         * @throws FMODException if the call to Studio::EventDescription::createInstance fails.
         */
        public EventInstance createInstance() {
            Arena arena = Arena.ofAuto();
            MemorySegment instancePointerPointer = arena.allocate(C_POINTER);

            FMODException.errCheck(
                    FMOD_Studio_EventDescription_CreateInstance(eventDescriptionPointer, instancePointerPointer));

            return new EventInstance(instancePointerPointer.get(C_POINTER, 0));
        }

        /**
         * Calls Studio::EventDescription::getPath to retrieve this event's full path
         * (e.g. {@code "event:/Weapons/AK47/AK47 1P Shot"}).
         *
         * <p>Requires the project's strings bank (Master.strings.bank) to already be
         * loaded, since the path is resolved from it.
         *
         * @return This event's full path.
         * @throws FMODException if the call to Studio::EventDescription::getPath fails.
         */
        public String getPath() {
            Arena arena = Arena.ofAuto();
            MemorySegment pathBuffer = arena.allocate(512);
            MemorySegment retrievedPointer = arena.allocate(C_INT);

            FMODException.errCheck(FMOD_Studio_EventDescription_GetPath(
                    eventDescriptionPointer, pathBuffer, (int) pathBuffer.byteSize(), retrievedPointer));

            return pathBuffer.getString(0);
        }

        /**
         * Calls Studio::EventDescription::getID to retrieve this event's GUID, formatted
         * as {@code "{xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}"}. This string can be passed
         * to {@link FMODSystem#getEvent} instead of a path, which does not require the
         * project's strings bank (Master.strings.bank) to be loaded.
         *
         * @return This event's GUID, formatted as a string.
         * @throws FMODException if the call to Studio::EventDescription::getID fails.
         */
        public String getId() {
            Arena arena = Arena.ofAuto();
            MemorySegment idPointer = FMOD_GUID.allocate(arena);

            FMODException.errCheck(FMOD_Studio_EventDescription_GetID(eventDescriptionPointer, idPointer));

            int data1 = FMOD_GUID.Data1(idPointer);
            short data2 = FMOD_GUID.Data2(idPointer);
            short data3 = FMOD_GUID.Data3(idPointer);
            byte[] data4 = new byte[8];
            for (int i = 0; i < 8; i++) {
                data4[i] = FMOD_GUID.Data4(idPointer, i);
            }

            return String.format(
                    "{%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x}",
                    data1,
                    data2,
                    data3,
                    data4[0] & 0xFF,
                    data4[1] & 0xFF,
                    data4[2] & 0xFF,
                    data4[3] & 0xFF,
                    data4[4] & 0xFF,
                    data4[5] & 0xFF,
                    data4[6] & 0xFF,
                    data4[7] & 0xFF);
        }
    }

    /**
     * A single playable instance of an FMOD Studio event, as returned by
     * {@link EventDescription#createInstance}.
     */
    public static class EventInstance {
        private final MemorySegment eventInstancePointer;

        private EventInstance(MemorySegment eventInstancePointer) {
            this.eventInstancePointer = eventInstancePointer;
        }

        /**
         * Calls Studio::EventInstance::start to begin playback of this event instance.
         *
         * @throws FMODException if the call to Studio::EventInstance::start fails.
         */
        public void start() {
            FMODException.errCheck(FMOD_Studio_EventInstance_Start(eventInstancePointer));
        }

        /**
         * Calls Studio::EventInstance::stop, letting the event fade out per its authored
         * envelope before stopping.
         *
         * @throws FMODException if the call to Studio::EventInstance::stop fails.
         */
        public void stop() {
            stop(false);
        }

        /**
         * Calls Studio::EventInstance::stop.
         *
         * @param immediate If true, stops playback immediately; otherwise allows the event
         *     to fade out per its authored envelope.
         * @throws FMODException if the call to Studio::EventInstance::stop fails.
         */
        public void stop(boolean immediate) {
            int mode = immediate ? FMOD_STUDIO_STOP_IMMEDIATE() : FMOD_STUDIO_STOP_ALLOWFADEOUT();
            FMODException.errCheck(FMOD_Studio_EventInstance_Stop(eventInstancePointer, mode));
        }

        /**
         * Calls Studio::EventInstance::getPlaybackState to retrieve the current playback
         * state of this event instance (0 = playing, 1 = sustaining, 2 = stopped,
         * 3 = starting, 4 = stopping).
         *
         * @return The current playback state.
         * @throws FMODException if the call to Studio::EventInstance::getPlaybackState fails.
         */
        public int getPlaybackState() {
            Arena arena = Arena.ofAuto();
            MemorySegment statePointer = arena.allocate(C_INT);

            FMODException.errCheck(FMOD_Studio_EventInstance_GetPlaybackState(eventInstancePointer, statePointer));

            return statePointer.get(C_INT, 0);
        }

        /**
         * Calls Studio::EventInstance::set3DAttributes to set this event instance's
         * position/velocity/orientation, used for panning and distance attenuation. Has no
         * effect on events that aren't authored as 3D (positional) in FMOD Studio.
         *
         * @param attributes The event instance's new position/velocity/orientation.
         * @throws FMODException if the call to Studio::EventInstance::set3DAttributes fails.
         */
        public void set3DAttributes(Attributes3D attributes) {
            Arena arena = Arena.ofConfined();
            try {
                MemorySegment attributesPointer = attributes.allocate(arena);
                FMODException.errCheck(FMOD_Studio_EventInstance_Set3DAttributes(eventInstancePointer, attributesPointer));
            } finally {
                arena.close();
            }
        }

        /**
         * Calls Studio::EventInstance::release. Marks the event instance for release once
         * it stops. Should be called once the caller no longer needs to interact with this
         * instance, typically right after {@link EventInstance#start}, for fire-and-forget
         * playback.
         *
         * @throws FMODException if the call to Studio::EventInstance::release fails.
         */
        public void release() {
            FMODException.errCheck(FMOD_Studio_EventInstance_Release(eventInstancePointer));
        }
    }
}
