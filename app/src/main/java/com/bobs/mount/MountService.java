package com.bobs.mount;

import static com.bobs.coord.CoordTransformer.ONE_DEG_IN_HOURS;
import static com.bobs.mount.TrackingState.*;

import java.util.Calendar;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.bobs.coord.CoordTransformer;
import com.bobs.coord.Target;
import com.bobs.io.NexstarAuxAdapter;
import com.bobs.io.NexstarAuxSerialAdapter;
import com.bobs.serialcommands.EnableCordWrap;
import com.bobs.serialcommands.Goto;
import com.bobs.serialcommands.GpsLat;
import com.bobs.serialcommands.GpsLinked;
import com.bobs.serialcommands.GpsLon;
import com.bobs.serialcommands.MountCommand;
import com.bobs.serialcommands.Move;
import com.bobs.serialcommands.PecPlayback;
import com.bobs.serialcommands.PecQueryAtIndex;
import com.bobs.serialcommands.PecQueryRecordDone;
import com.bobs.serialcommands.PecSeekIndex;
import com.bobs.serialcommands.PecStartRecording;
import com.bobs.serialcommands.PecStopRecording;
import com.bobs.serialcommands.QueryAltMcPosition;
import com.bobs.serialcommands.QueryAzMcPosition;
import com.bobs.serialcommands.QueryCordWrap;
import com.bobs.serialcommands.QueryCordWrapPos;
import com.bobs.serialcommands.QuerySlewDone;
import com.bobs.serialcommands.SetAltMcPosition;
import com.bobs.serialcommands.SetAzMcPosition;
import com.bobs.serialcommands.SetGuideRate;

/**
 * Responsible for interacting with the mount using a {@link NexstarAuxSerialAdapter}
 * and providing high level features by means of various {@link MountCommand}s
 */
@Service
public class MountService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MountService.class);
    private static final int DEFAULT_PEC_POLL_INTERVAL = 5000;

    /**
     * This is the mount that the service is managing. There is only 1 mount instance.
     */
    @Autowired
    private Mount mount;

    @Autowired
    private NexstarAuxAdapter auxAdapter;

    private int pecPollInterval = DEFAULT_PEC_POLL_INTERVAL;

    private static boolean SLEW_LIMIT_ENABLED = true;

    /**
     * The Sync operation is basically 'alignment'. It tells the mount where it is currently pointing.
     * This is used when unparking a mount or when using astrometric plate solving to perform alignment.
     *
     * @param target The Target contining the RA/DEC coordinates to sync to.
     */
    @Async
    public void sync(Target target) {
        if (!mount.isLocationSet()) {
            mount.setError(true);
            mount.setStatusMessage("Mount location is not set. Please connect GPS or set location");
            throw new IllegalStateException("Mount location is not set. Please connect GPS or set location");
        }
        LOGGER.info("Syncing to RA:{} DEC:{}", target.getRaHours(), target.getDec());
        logSyncDifference(target,mount);
        if (mount.getTrackingState().equals(TrackingState.IDLE)) {
            startTracking();
        }
        CoordTransformer coordTransformer = new CoordTransformer();
        if (mount.getTrackingMode().equals(TrackingMode.EQ_NORTH)) {
            double azimuthAxisDegrees = coordTransformer.convertRaFromDegToNexstarAzimuthAngle(
                    Calendar.getInstance(),
                    mount.getLongitude(),
                    coordTransformer.convertRaHoursToDeg(target.getRaHours()));
            double altitudeAxisDegrees = coordTransformer.convertDecToPositionAngleForEqNorth(target.getDec());
            auxAdapter.queueCommand(new SetAltMcPosition(mount, altitudeAxisDegrees));
            auxAdapter.queueCommand(new SetAzMcPosition(mount, azimuthAxisDegrees));
        } else {
            throw new IllegalStateException("ONly EQ north tracking mode supported");
        }
        queryMountState();
        mount.setAligned(true);
        mount.setError(false);
    }

    /**
     * Temporary: used to collect differences in sync points. Useful for future mount modeling.
     * @param target
     * @param mount
     */
    private void logSyncDifference(Target target, Mount mount) {
        CoordTransformer coordTransformer = new CoordTransformer();
        coordTransformer.populateAltAzFromRaDec(mount);
        LOGGER.debug("**SYNC DIFF-header**,alt,az,ra-diff,dec-diff");
        LOGGER.debug("**SYNC DIFF-values**,{},{},{},{}",mount.getAlt(), mount.getAz(), target.getRaHours() - mount.getRaHours(), target.getDec() - mount.getDecDegrees());
    }


    /**
     * Slew the mount to a new position. If the target location is less than 1deg (approx) from the current location
     * then a slow slew only is used, otherwise a fast slew followed by a slow slew is used.
     * This is a non blocking operation. Clients should perform regular queries to determine if the slew is complete.
     *
     * @param target The {@link Target} to go to.
     * @param parkSlew if True then the slew is to park the scope
     */
    @Async
    public void slew(Target target, boolean parkSlew) {
        if (!mount.isAligned()) {
            mount.setError(true);
            mount.setStatusMessage("Error: Please sync mount before slewing");
            throw new IllegalStateException("Please sync/align the mount before slewing");
        }
        LOGGER.info("Slewing to RA:{} DEC:{}", target.getRaHours(), target.getDec());
        mount.setTrackingState(parkSlew ? PARKING : SLEWING);
        if (fastSlewRequired(mount, target)) {
            slewAndWait(target, true);
        }
        if(stillSlewingOrParking()) {
            slewAndWait(target, false);
        }
        mount.setTrackingState(parkSlew ? PARKED : TRACKING);
    }

    private boolean stillSlewingOrParking() {
        return mount.getTrackingState()==PARKING || mount.getTrackingState()==SLEWING;
    }

    /**
     * Blocking operation to slew and wait until complete.
     * Waits for each axis to complete.
     *
     * @param target the @{link Target} to slew to
     * @param fast   true/false for fast/slow slew
     */
    private void slewAndWait(Target target, boolean fast) {
        mount.setAltSlewInProgress(true);
        mount.setAzSlewInProgress(true);
        CoordTransformer coordTransformer = new CoordTransformer();
        double azimuthAxisDegrees = coordTransformer.convertRaFromDegToNexstarAzimuthAngle(
                mount.getCalendarProvider().provide(),
                mount.getLongitude(),
                coordTransformer.convertRaHoursToDeg(target.getRaHours()));
        double altitudeAxisDegrees = target.getDec();
        LOGGER.debug("Slew to nextar-alt-ax-deg:{} nextar-az-ax-deg:{} ", altitudeAxisDegrees, azimuthAxisDegrees);
        auxAdapter.queueCommand(new Goto(mount, altitudeAxisDegrees, Axis.ALT, fast));
        auxAdapter.queueCommand(new Goto(mount, azimuthAxisDegrees, Axis.AZ, fast));
        while (mount.isSlewing()) {
            if (mount.isAzSlewInProgress()) {
                auxAdapter.queueCommand(new QuerySlewDone(mount, Axis.AZ));
            }
            if (mount.isAltSlewInProgress()) {
                auxAdapter.queueCommand(new QuerySlewDone(mount, Axis.ALT));
            }
            sleep(1000); //artifical rate limit
        }
    }


    /**
     * Monitor a slew and Enforce an ALT slew limit for the ALT axis to prevent damage.
     * If limit detected then send serial commands to abort any motion
     */
    @Scheduled(fixedDelay = 1000)
    public void monitorSlew() {
        if(mount.isSlewing()) {
            auxAdapter.queueCommand(new QueryAzMcPosition(mount));
            auxAdapter.queueCommand(new QueryAltMcPosition(mount));
            auxAdapter.waitForQueueEmpty();
            CoordTransformer coordTransformer = new CoordTransformer();
            coordTransformer.populateAltAzFromRaDec(mount);
            LOGGER.debug("Monitoring Slew limit ALTAZ {} {}",mount.getAlt(), mount.getAz());
            if (SLEW_LIMIT_ENABLED && mount.getAlt() != null && mount.getAlt() < mount.getSlewLimitAlt()) {
                auxAdapter.queueCommand(new Move(mount, 0, Axis.ALT, true));
                auxAdapter.queueCommand(new Move(mount, 0, Axis.AZ, true));
                mount.setAltSlewInProgress(false);
                mount.setAzSlewInProgress(false);
                mount.setError(true);
                mount.setStatusMessage("ABORTING: ALT slew limit " + mount.getAlt()) ;
                throw new RuntimeException("Slew Limit Reached " + mount.getAlt() + " " + mount.getAz());
            }
        }
    }

    /**
     * Returns true if any axis needs to move more than 1 degree.
     *
     * @param mount
     * @param target
     * @return
     */
    private boolean fastSlewRequired(Mount mount, Target target) {
        double decDiff = Math.abs(mount.getDecDegrees() - target.getDec());
        double raDiff = Math.abs(mount.getRaHours() - target.getRaHours());
        return decDiff > 1 || raDiff > ONE_DEG_IN_HOURS;
    }

    /**
     * Park will slew the mount to the RA/DEC specified in the {@link Target} and then stop tracking.
     * The mount state is also persisted since this is probably one of the last operations before stopping the application.
     *
     * @param target
     * @return
     */
    @Async
    public Target park(Target target) {
        if (!mount.isAligned()) {
            mount.setStatusMessage("Error: Please sync mount before slewing");
            mount.setError(true);
            throw new IllegalStateException("Please sync/align the mount before moving");
        }
        LOGGER.warn("PARKING MOUNT");
        mount.setTrackingState(PARKING);
        slew(target, true);
        auxAdapter.queueCommand(new SetGuideRate(mount, MountCommand.AZM_BOARD, GuideRate.OFF));
        auxAdapter.queueCommand(new SetGuideRate(mount, MountCommand.ALT_BOARD, GuideRate.OFF));
        auxAdapter.waitForQueueEmpty();
        mount.setTrackingState(PARKED);
        mount.saveState();
        return target;
    }

    /**
     * Unpark will start tracking and sync to the location passed in the Target.
     *
     * @param target
     */
    public void unpark(Target target) {
        LOGGER.info("Unparking and syncing to {}, {}", target.getRaHours(), target.getDec());
        startTracking();
        sync(target);
        auxAdapter.queueCommand(new EnableCordWrap(mount)); //not sure if EnableCordWrap is really needed after unpark but if not done sometimes cordwrap does not seem to actually be enabled
        auxAdapter.queueCommand(new QueryCordWrapPos(mount));
        //TODO: If option set to start PEC on unpark then send pecplayback command here
    }

    /**
     * Start tracking mode depending on the mount's trackingMode. Note: This could also switch off tracking since OFF is a valid guide rate
     */
    public void startTracking() {
        if (TrackingMode.EQ_NORTH.equals(mount.getTrackingMode())) {
            LOGGER.info("Starting EQ-NORTH sidereal tracking");
            auxAdapter.queueCommand(new SetGuideRate(mount, MountCommand.AZM_BOARD, GuideRate.OFF));
            auxAdapter.queueCommand(new SetGuideRate(mount, MountCommand.ALT_BOARD, GuideRate.OFF));
            auxAdapter.queueCommand(new SetGuideRate(mount, MountCommand.AZM_BOARD, mount.getGuideRate()));
            if (mount.getGuideRate() == GuideRate.OFF) {
                mount.setTrackingState(TrackingState.IDLE);
            } else {
                mount.setTrackingState(TRACKING);
            }
        } else {
            throw new UnsupportedOperationException("Currently only EQ north mode is supported.");
        }
    }

    /**
     * Connect to the mount and enable some default features such as cordwrap.
     *
     * @return
     */
    public boolean connect() {
        mount.setTrackingMode(TrackingMode.EQ_NORTH);
        mount.setError(false);
        mount.setStatusMessage(null);
        if (!auxAdapter.isConnected()) {
            auxAdapter.setSerialPortName(mount.getSerialPort());
            LOGGER.info("Starting serial adapter thread");
            new Thread(auxAdapter).start();
            sleep(1000);//wait for connect
        }
        LOGGER.debug("Enabling Cordwrap");
        auxAdapter.queueCommand(new EnableCordWrap(mount));
        auxAdapter.queueCommand(new QueryCordWrap(mount));
        if (mount.getTrackingState() == TrackingState.TRACKING) {
            startTracking();
        }
        return true;
    }

    /**
     * Perform a query to the mount to determine current position if connected.
     */
    @Scheduled(fixedDelay = 60000)
    public void queryMountState() {
        if (auxAdapter.isConnected() && !mount.isSlewing()) {
            if (mount.isLocationSet() && mount.getTrackingMode() != null) {
                auxAdapter.queueCommand(new QueryAzMcPosition(mount));
                auxAdapter.queueCommand(new QueryAltMcPosition(mount));
            }
            if (mount.isGpsInfoOld()) {
                LOGGER.info("Querying GPS for new data");
                queryGps();
            }
            auxAdapter.queueCommand(new QueryCordWrap(mount));
        }
        LOGGER.info("Queried mount state RA {} DEC {} Cordwrap {}", mount.getRaHours(),mount.getDecDegrees(), mount.isCordWrapEnabled());
    }

    /**
     * Query the GPS module and update the mount
     */
    private void queryGps() {
        LOGGER.info("GPS locating satellites");
        auxAdapter.queueCommand(new GpsLinked(mount));
        auxAdapter.waitForQueueEmpty();
        if (mount.isGpsConnected()) {
            LOGGER.info("GPS connected");
            auxAdapter.queueCommand(new GpsLat(mount));
            auxAdapter.queueCommand(new GpsLon(mount));
            mount.setLocationSet(true);
            mount.setGpsUpdateTime(new Date());
            LOGGER.info("GPS location updated");
        }
    }

    /**
     * Handle a guide request described in the target passed. This call is blocking.
     * Pulse guiding is implemented with a Thread.wait. This is not ideal but I cannot get the pulse guide serial commands to work with my mount
     *
     * @param target
     */
    public void guide(Target target) {
        if(mount.getTrackingState()==PARKING || mount.getTrackingState()==SLEWING) {
            LOGGER.warn("Cannot process guide command while mount is slewing");
        } else {
            LOGGER.debug("Guiding {} {}ms", target.getMotion(), target.getGuidePulseDurationMs());
            Axis axis = Axis.ALT;
            boolean positive = true;
            if ("east".equals(target.getMotion()) || "west".equals(target.getMotion())) {
                axis = Axis.AZ;
            }
            if ("east".equals(target.getMotion()) || "south".equals(target.getMotion())) {
                positive = false;
            }
            auxAdapter.queueCommand(new Move(mount, 1, axis, positive));
            sleep(target.getGuidePulseDurationMs().intValue());
            auxAdapter.queueCommand(new Move(mount, 0, axis, positive));
        }
    }

    /**
     * Non blocking call to send motion requests to the scope. The motion will continue until a target with a rate of 0 is passed.
     *
     * @param target
     */
    @Async
    public void moveAxis(Target target) {
        String direction = target.getMotion();
        LOGGER.info("Motion request {}, {}", direction, target.getMotionRate());
        Axis axis = Axis.AZ;
        if ("north".equals(direction) || "south".equals(direction)) {
            axis = Axis.ALT;
        }
        int rate = 0;
        switch (target.getMotionRate()) {
            case 0:
                rate = 1;
                break;
            case 1:
                rate = 3;
                break;
            case 2:
                rate = 6;
                break;
            case 3:
                rate = 9;
                break;
        }
        boolean positive = "north".equals(direction) || "west".equals(direction);
        if ("abort".equals(direction)) {
            LOGGER.info("Stopping Motion request {}, {}", direction, target.getMotionRate());
            auxAdapter.queueCommand(new Move(mount, 0, Axis.ALT, positive));
            auxAdapter.queueCommand(new Move(mount, 0, Axis.AZ, positive));
        } else {
            auxAdapter.queueCommand(new Move(mount, rate, axis, positive));
        }
        queryMountState();
    }


    /**
     * Returns the current known mount state if connected.
     *
     * @return
     */
    public Mount getMount() {
        if (!auxAdapter.isConnected()) {
            mount.setError(true);
            mount.setStatusMessage("NOT CONNECTED");
            throw new IllegalStateException("Not Connected");
        }
//        LOGGER.debug("Getting mount RA:DEC {} {}", mount.getRaHours(), mount.getDecDegrees());
        return mount;
    }

    /**
     * Utility method for pulse guiding
     *
     * @param ms
     */
    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            LOGGER.info("Sleep interrupted");
        }
    }

    /**
     * Update the mount. Only 6 properties are updateable. SerialPort, Guiderate, SlewLimits(x2) lat/lon and Pec status
     *
     * @param mountUpdates
     * @return
     */
    @Async
    public void updateMount(Mount mountUpdates) {
        if (mountUpdates.getSerialPort() != null && mountUpdates.getSerialPort() != mount.getSerialPort()) {
            mount.setSerialPort(mountUpdates.getSerialPort());
        }
        if (mountUpdates.getGuideRate() != null && mountUpdates.getGuideRate() != mount.getGuideRate()) {
            mount.setGuideRate(mountUpdates.getGuideRate());
            startTracking();
        }
        if (mountUpdates.getSlewLimitAlt() != null && mountUpdates.getSlewLimitAlt() != mount.getSlewLimitAlt()) {
            mount.setSlewLimitAlt(mountUpdates.getSlewLimitAlt());
        }
        if (mountUpdates.getSlewLimitAz() != null && mountUpdates.getSlewLimitAz() != mount.getSlewLimitAz()) {
            mount.setSlewLimitAz(mountUpdates.getSlewLimitAz());
        }
        if (mountUpdates.getPecMode() != null && mountUpdates.getPecMode() != mount.getPecMode()) {
            startPecOperation(mountUpdates.getPecMode());
        }
        if (mountUpdates.getLatitude() != null || mountUpdates.getLongitude() != null) {
            mount.setLongitude(mountUpdates.getLongitude());
            mount.setLatitude(mountUpdates.getLatitude());
            mount.setLocationSet(true);
        }
    }


    /**
     * Start a PEC operation depending on the mount state
     * @param pecMode
     */
    public void startPecOperation(PecMode pecMode) {
        LOGGER.info("Setting PEC mode to {}", pecMode);
        switch (pecMode) {
            case INDEXING:
                mount.setPecIndexFound(false);
                auxAdapter.queueCommand(new PecSeekIndex(mount));
                while (!mount.isPecIndexFound()) {
                    auxAdapter.queueCommand(new PecQueryAtIndex(mount));
                    sleep(pecPollInterval);
                }
                break;
            case RECORDING:
                mount.setPecMode(PecMode.RECORDING);
                auxAdapter.queueCommand(new PecStartRecording(mount));
                while (mount.getPecMode().equals(PecMode.RECORDING)) {
                    auxAdapter.queueCommand(new PecQueryRecordDone(mount));
                    sleep(pecPollInterval);
                }
                break;
            case PLAYING:
                if (mount.getPecMode() == PecMode.RECORDING) {
                    LOGGER.warn("Mount currently recording, not starting playback");
                } else {
                    auxAdapter.queueCommand(new PecPlayback(mount, true));
                }
                break;
            case IDLE:
                auxAdapter.queueCommand(new PecStopRecording(mount));
                auxAdapter.queueCommand(new PecPlayback(mount, false));
                break;
        }
    }

    public void setPecPollInterval(int pecPollInterval) {
        this.pecPollInterval = pecPollInterval;
    }
}
