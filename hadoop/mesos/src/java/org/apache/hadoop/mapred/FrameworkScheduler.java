package org.apache.hadoop.mapred;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.TaskStatus.State;
import org.apache.hadoop.mapred.TaskTrackerStatus;
import org.apache.hadoop.mapreduce.server.jobtracker.TaskTracker;
import org.apache.hadoop.net.Node;

import org.apache.mesos.Protos.CommandInfo;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.ExecutorInfo;
import org.apache.mesos.Protos.FrameworkID;
import org.apache.mesos.Protos.FrameworkInfo;
import org.apache.mesos.Protos.MasterInfo;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.Resource;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.Status;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.TaskState;
import org.apache.mesos.Protos.Value;

public class FrameworkScheduler implements Scheduler {
  public static final Log LOG =
    LogFactory.getLog(FrameworkScheduler.class);
  public static final long KILL_UNLAUNCHED_TASKS_SLEEP_TIME = 2000;

  private static class MesosTask {
    final boolean isMap;
    final TaskID mesosId;
    final String host;
    final long creationTime;

    TaskAttemptID hadoopId;

    MesosTask(boolean isMap, TaskID mesosId, String host) {
      this.isMap = isMap;
      this.mesosId = mesosId;
      this.host = host;
      this.creationTime = System.currentTimeMillis();
    }

    boolean isAssigned() {
      return hadoopId != null;
    }

    void assign(Task task) {
      hadoopId = task.getTaskID();
    }
  }

  private static class TaskTrackerInfo {
    SlaveID mesosSlaveId;
    List<MesosTask> maps = new LinkedList<MesosTask>();
    List<MesosTask> reduces = new LinkedList<MesosTask>();
    int maxMaps = 1;
    int maxReduces = 1;

    public TaskTrackerInfo(SlaveID mesosSlaveId) {
      this.mesosSlaveId = mesosSlaveId;
    }

    void add(MesosTask nt) {
      if (nt.isMap)
        maps.add(nt);
      else
        reduces.add(nt);
    }

    public void remove(MesosTask nt) {
      if (nt.isMap)
        maps.remove(nt);
      else
        reduces.remove(nt);
    }
  }

  private class KillTimedOutTasksThread extends Thread {
    @Override
    public void run() {
      while (running) {
        killTimedOutTasks();
        try { Thread.sleep(KILL_UNLAUNCHED_TASKS_SLEEP_TIME); }
        catch (Exception e) {}
      }
    }
  }

  private MesosScheduler mesosSched;
  private SchedulerDriver driver;
  private FrameworkID frameworkId;
  private Configuration conf;
  private JobTracker jobTracker;
  private boolean running;
  private AtomicInteger nextMesosTaskId = new AtomicInteger(0);

  private int cpusPerTask;
  private int memPerTask;
  private long localityWait;

  private Map<String, TaskTrackerInfo> ttInfos =
    new HashMap<String, TaskTrackerInfo>();

  private Map<TaskAttemptID, MesosTask> hadoopIdToMesosTask =
    new HashMap<TaskAttemptID, MesosTask>();
  private Map<TaskID, MesosTask> mesosIdToMesosTask =
    new HashMap<TaskID, MesosTask>();

  // Counts of various kinds of Mesos tasks
  // TODO: Figure out a better way to keep track of these
  int unassignedMaps = 0;
  int unassignedReduces = 0;
  int assignedMaps = 0;
  int assignedReduces = 0;

  // Variables used for delay scheduling
  boolean lastMapWasLocal = true;
  long timeWaitedForLocalMap = 0;
  long lastCanLaunchMapTime = -1;

  public FrameworkScheduler(MesosScheduler mesosSched) {
    this.mesosSched = mesosSched;
    this.conf = mesosSched.getConf();
    this.jobTracker = mesosSched.jobTracker;
    cpusPerTask = conf.getInt("mapred.mesos.task.cpus", 1);
    memPerTask = conf.getInt("mapred.mesos.task.mem", 1024);
    localityWait = conf.getLong("mapred.mesos.localitywait", 5000);
  }

  @Override
  public void registered(SchedulerDriver d,
                         FrameworkID frameworkId,
                         MasterInfo masterInfo) {
    this.driver = d;
    this.frameworkId = frameworkId;
    LOG.info("Registered with Mesos, with framework ID " + frameworkId);
    running = true;
    new KillTimedOutTasksThread().start();
  }

  @Override
  public void reregistered(SchedulerDriver driver, MasterInfo masterInfo) {}

  @Override
  public void disconnected(SchedulerDriver d) {}

  public void cleanUp() {
    running = false;
  }

  private static Resource makeResource(String name, double value) {
    return Resource.newBuilder().setName(name).setScalar(
        Value.Scalar.newBuilder().setValue(value).build()
    ).setType(Value.Type.SCALAR).build();
  }

  private static double getResource(Collection<Resource> resources, String name) {
    for (Resource r : resources) {
      if (r.getName().equals(name)) {
        return r.getScalar().getValue();
      }
    }
    throw new IndexOutOfBoundsException(name);
  }

  private static double getResource(Offer offer, String name) {
    return getResource(offer.getResourcesList(), name);
  }

  private static double getResource(TaskInfo task, String name) {
    return getResource(task.getResourcesList(), name);
  }

  @Override
  public void resourceOffers(SchedulerDriver d, List<Offer> offers) {
    try {
      synchronized(jobTracker) {

        int numOffers = (int) offers.size();
        double[] cpus = new double[numOffers];
        double[] mem = new double[numOffers];

        // Count up the amount of free CPUs and memory on each node
        for (int i = 0; i < numOffers; i++) {
          Offer offer = offers.get(i);
          LOG.info("Got resource offer " + offer.getId().getValue());
          cpus[i] = getResource(offer, "cpus");
          mem[i] = getResource(offer, "mem");
          LOG.info("Offer is for " + cpus[i] + " cpus and " + mem[i] +
                   " mem on " + offer.getHostname());
        }

        // Assign tasks to the nodes in a round-robin manner, and stop when we
        // are unable to assign a task to any node.
        // We do this by keeping a linked list of indices of nodes for which
        // we are still considering assigning tasks. Whenever we can't find a
        // new task for a node, we remove it from the list. When the list is
        // empty, no further assignments can be made. This algorithm was chosen
        // because it minimizing the amount of scanning we need to do if we
        // get a large set of offered nodes.
        List<Integer> indices = new LinkedList<Integer>();
        List<List<TaskInfo>> replies =
            new ArrayList<List<TaskInfo>>(numOffers);
        for (int i = 0; i < numOffers; i++) {
          indices.add(i);
          replies.add(new ArrayList<TaskInfo>());
        }
        while (indices.size() > 0) {
          for (Iterator<Integer> it = indices.iterator(); it.hasNext();) {
            int i = it.next();
            Offer offer = offers.get(i);
            TaskInfo task = findTask(
                offer.getSlaveId(), offer.getHostname(), cpus[i], mem[i]);
            if (task != null) {
              LOG.info("Launching task for offer " + offer.getId().getValue());
              cpus[i] -= getResource(task, "cpus");
              mem[i] -= getResource(task, "mem");
              replies.get(i).add(task);
            } else {
              LOG.info("Could not find task for offer " +
                       offer.getId().getValue());
              it.remove();
            }
          }
        }

        for (int i = 0; i < numOffers; i++) {
          OfferID offerId = offers.get(i).getId();
          Status status = d.launchTasks(offerId, replies.get(i));
          if (status != Status.DRIVER_RUNNING) {
            LOG.warn("SchedulerDriver returned irregular status: " + status);
          }
        }
      }
    } catch(Exception e) {
      LOG.error("Error in resourceOffer", e);
    }
  }

  private TaskTrackerInfo getTaskTrackerInfo(String host, SlaveID slaveId) {
    if (ttInfos.containsKey(host)) {
      return ttInfos.get(host);
    } else {
      TaskTrackerInfo info = new TaskTrackerInfo(slaveId.toBuilder().build());
      LOG.info("Created new TaskTrackerInfo for " + host + " / " +
          slaveId.getValue());
      ttInfos.put(host, info);
      return info;
    }
  }

  // Find a single task for a given node. Assumes JobTracker is locked.
  private TaskInfo findTask(
      SlaveID slaveId, String host, double cpus, double mem) {
    if (cpus < cpusPerTask || mem < memPerTask) {
      LOG.info("Cannot findTask because too few cpus/memory on " + host);
      return null; // Too few resources are left on the node
    }

    TaskTrackerInfo ttInfo = getTaskTrackerInfo(host, slaveId);

    // Pick whether to launch a map or a reduce based on available tasks
    String taskType = null;
    boolean haveMaps = canLaunchMap(host);
    boolean haveReduces = canLaunchReduce(host);
    LOG.info("Looking at " + host + ": haveMaps=" + haveMaps +
             ", haveReduces=" + haveReduces);
    if (!haveMaps && !haveReduces) {
      return null;
    } else if (haveMaps && !haveReduces) {
      taskType = "map";
    } else if (haveReduces && !haveMaps) {
      taskType = "reduce";
    } else {
      float mapToReduceRatio = 1;
      if (ttInfo.reduces.size() < ttInfo.maps.size() / mapToReduceRatio)
        taskType = "reduce";
      else
        taskType = "map";
    }
    LOG.info("Task type chosen: " + taskType);

    // Get a Mesos task ID for the new task
    TaskID mesosId = newMesosTaskId();

    // Remember that it is launched
    boolean isMap = taskType.equals("map");
    if (isMap) {
      unassignedMaps++;
    } else {
      unassignedReduces++;
    }
    MesosTask nt = new MesosTask(isMap, mesosId, host);
    mesosIdToMesosTask.put(mesosId, nt);
    ttInfo.add(nt);

    LOG.info("Launching Mesos task " + mesosId.getValue() +
             " as " + taskType + " on " + host);

    // Create a task description to pass back to Mesos.
    return TaskInfo.newBuilder()
        .setTaskId(mesosId)
        .setSlaveId(slaveId)
        .setName("task " + mesosId.getValue() + " (" + taskType + ")")
        .addResources(makeResource("cpus", cpusPerTask))
        .addResources(makeResource("mem", memPerTask))
        .setExecutor(getExecutorInfo())
        .build();
  }

  private TaskID newMesosTaskId() {
    return TaskID.newBuilder().setValue(
        "" + nextMesosTaskId.getAndIncrement()
    ).build();
  }

  public FrameworkInfo getFrameworkInfo() {
    String name = "Hadoop: " + jobTracker.getTrackerIdentifier() +
      " (RPC port: " + jobTracker.port + "," +
      " web UI port: " + jobTracker.infoPort + ")";

    return FrameworkInfo.newBuilder().setUser("").setName(name).build();
  }

  private static final ExecutorID EXECUTOR_ID =
      ExecutorID.newBuilder().setValue("default").build();

  public ExecutorInfo getExecutorInfo() {
    try {
      String execPath = new File("bin/mesos-executor").getCanonicalPath();
      byte[] initArg = conf.get("mapred.job.tracker").getBytes("US-ASCII");
      return ExecutorInfo.newBuilder()
        .setCommand(CommandInfo.newBuilder()
                    .setValue(execPath).build())
        .setData(com.google.protobuf.ByteString.copyFrom(initArg))
        .setExecutorId(EXECUTOR_ID)
        .build();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  // TODO: Make this return a count instead of a boolean?
  // TODO: Cache result for some time so we don't iterate through all jobs
  // and tasks every time we get a resource offer?
  private boolean canLaunchMap(String host) {
    // Check whether the TT is saturated on maps
    TaskTrackerInfo ttInfo = ttInfos.get(host);
    if (ttInfo == null) {
      throw new RuntimeException("Expecting TaskTrackerInfo for host " + host);
    }

    if (ttInfo.maps.size() >= ttInfo.maxMaps) {
      LOG.info("No maps because too many running");
      return false;
    }

    // Compute the total demand for maps to make sure we don't exceed it
    Collection<JobInProgress> jobs = jobTracker.jobs.values();
    int neededMaps = 0;
    for (JobInProgress job : jobs) {
      if (job.getStatus().getRunState() == JobStatus.RUNNING) {
        neededMaps += job.pendingMaps();
      }
    }
    // TODO (!!!): Count speculatable tasks and add them to neededMaps
    // For now, we just add 1
    if (jobs.size() > 0)
      neededMaps += 1;

    LOG.info("Seen " + neededMaps + " versus " +
        unassignedMaps + " unassigned");

    if (unassignedMaps < neededMaps) {
      // 0. check for a failed map task to place. These tasks are not included
      // in the "normal" lists of tasks in the JobInProgress object.
      for (JobInProgress job: jobs) {
        int state = job.getStatus().getRunState();
        if (job.failedMaps != null && state == JobStatus.RUNNING) {
          for (TaskInProgress tip : job.failedMaps) {
            if (!tip.hasFailedOnMachine(host)) {
              return true;
            }
          }
        }
      }

      int maxLevel = Integer.MAX_VALUE;
      // Look for a map with the required level
      for (JobInProgress job: jobs) {
        int state = job.getStatus().getRunState();
        if (state == JobStatus.RUNNING) {
          int availLevel = availableMapLevel(job, host, maxLevel);
          LOG.info("available level for job " + job.getJobID() + " is " +
              availLevel);
          if (availLevel != -1) {
            lastMapWasLocal = (availLevel == 0);
            return true;
          }
        }
      }
    }

    // If we didn't launch any tasks, but there are pending jobs in the queue,
    // ensure that at least one TaskTracker is running to execute setup tasks
    int numTrackers = jobTracker.getClusterStatus().getTaskTrackers();
    if (jobs.size() > 0 && numTrackers == 0 && totalMesosTasks() == 0) {
      LOG.info("Going to launch map task for setup / cleanup");
      return true;
    }

    return false;
  }

  private int totalMesosTasks() {
    return unassignedMaps + unassignedReduces + assignedMaps + assignedReduces;
  }

  // TODO: Make this return a count instead of a boolean?
  // TODO: Cache result for some time so we don't iterate through all jobs
  // and tasks every time we get a resource offer?
  private boolean canLaunchReduce(String host) {
    // Don't launch a reduce if we've only got one "slot"
    // available. We approximate this by not launching any reduce
    // tasks if there is only one TaskTracker.
    if (jobTracker.getClusterStatus().getTaskTrackers() <= 1) {
      Collection<JobInProgress> jobs = jobTracker.jobs.values();
      for (JobInProgress job : jobs) {
        if (job.getStatus().getRunState() == JobStatus.RUNNING) {
          if (job.pendingMaps() > 0) {
            return false;
          }
        }
      }
    }

    // Check whether the TT is saturated on reduces
    TaskTrackerInfo ttInfo = ttInfos.get(host);
    if (ttInfo == null) {
      throw new RuntimeException("Expecting TaskTrackerInfo for host " + host);
    }

    if (ttInfo.reduces.size() >= ttInfo.maxReduces) {
      return false;
    }

    // Compute total demand for reduces, to make sure we don't exceed it
    Collection<JobInProgress> jobs = jobTracker.jobs.values();
    int neededReduces = 0;
    for (JobInProgress job : jobs) {
      if (job.getStatus().getRunState() == JobStatus.RUNNING) {
        neededReduces += job.pendingReduces();
      }
    }
    // TODO (!!!): Count speculatable tasks and add them to neededReduces
    // For now, we just add 1
    if (jobs.size() > 0)
      neededReduces += 1;

    if (neededReduces > unassignedReduces) {
      // Find a reduce to launch
      for (JobInProgress job: jobs) {
        int state = job.getStatus().getRunState();
        if (state == JobStatus.RUNNING && hasReduceToLaunch(job)) {
          return true;
        }
      }
    }

    return false;
  }

  @Override
  public void statusUpdate(SchedulerDriver d, org.apache.mesos.Protos.TaskStatus status) {
    TaskState state = status.getState();
    LOG.info("Task " + status.getTaskId().getValue() + " is " + state);
    if (state == TaskState.TASK_FINISHED || state == TaskState.TASK_FAILED ||
        state == TaskState.TASK_KILLED || state == TaskState.TASK_LOST) {
      synchronized (jobTracker) {
        TaskID mesosId = status.getTaskId();
        MesosTask nt = mesosIdToMesosTask.get(mesosId);
        if (nt == null) {
          LOG.warn("Received status update for unknown task " +
              status.getTaskId());
        } else {
          removeTask(nt);
        }
      }
    }
  }

  /**
   * Called by JobTracker to ask us to launch tasks on a heartbeat.
   *
   * This is currently kind of silly; would be better to grab tasks when
   * we respond to the Mesos assignment, but then we'd need to be willing to
   * launch TaskTrackers everywhere
   */
  public List<Task> assignTasks(TaskTracker tt) {
    synchronized (jobTracker) {
      try {
        Collection<JobInProgress> jobs = jobTracker.jobs.values();

        TaskTrackerStatus tts = tt.getStatus();
        String host = tts.getHost();

        TaskTrackerInfo ttInfo = ttInfos.get(host);
        if (ttInfo == null) {
          throw new RuntimeException(
              "Expecting TaskTrackerInfo for host " + host);
        }

        ttInfo.maxMaps = tts.getMaxMapSlots();
        ttInfo.maxReduces = tts.getMaxReduceSlots();

        LOG.info(host + " has " + ttInfo.maxMaps + " map slots and " +
                 ttInfo.maxReduces + " reduce slots");

        int clusterSize = jobTracker.getClusterStatus().getTaskTrackers();
        int numHosts = jobTracker.getNumberOfUniqueHosts();

        // Assigned tasks
        List<Task> assignedTasks = new ArrayList<Task>();

        // Identify unassigned maps and reduces on this TT
        List<MesosTask> assignableMaps = new ArrayList<MesosTask>();
        List<MesosTask> assignableReduces = new ArrayList<MesosTask>();
        for (MesosTask nt: ttInfo.maps)
          if (!nt.isAssigned())
            assignableMaps.add(nt);
        for (MesosTask nt: ttInfo.reduces)
          if (!nt.isAssigned())
            assignableReduces.add(nt);

        LOG.info("Assigning tasks for " + host + " with " +
                 assignableMaps.size() + " map slots and " +
                 assignableReduces.size() + " reduce slots");

        // Get some iterators for the unassigned tasks
        Iterator<MesosTask> mapIter = assignableMaps.iterator();
        Iterator<MesosTask> reduceIter = assignableReduces.iterator();

        // Go through jobs in FIFO order and look for tasks to launch
        for (JobInProgress job: jobs) {
          if (job.getStatus().getRunState() == JobStatus.RUNNING) {
            // If the node has unassigned maps, try to launch map tasks
            while (mapIter.hasNext()) {
              Task task = job.obtainNewMapTask(tts, clusterSize, numHosts);
              if (task != null) {
                MesosTask nt = mapIter.next();
                nt.assign(task);
                unassignedMaps--;
                assignedMaps++;
                hadoopIdToMesosTask.put(task.getTaskID(), nt);
                assignedTasks.add(task);
                task.extraData = "" + nt.mesosId.getValue();
              } else {
                break;
              }
            }
            // If the node has unassigned reduces, try to launch reduce tasks
            while (reduceIter.hasNext()) {
              Task task = job.obtainNewReduceTask(tts, clusterSize, numHosts);
              if (task != null) {
                MesosTask nt = reduceIter.next();
                nt.assign(task);
                unassignedReduces--;
                assignedReduces++;
                hadoopIdToMesosTask.put(task.getTaskID(), nt);
                assignedTasks.add(task);
                task.extraData = "" + nt.mesosId.getValue();
              } else {
                break;
              }
            }
          }
        }

        return assignedTasks;
      } catch (IOException e) {
        LOG.error("IOException in assignTasks", e);
        return null;
      }
    }
  }

  private void removeTask(MesosTask nt) {
    if (nt == null) return;
    synchronized (jobTracker) {
      LOG.info("Removing task with mesos id " + nt.mesosId +
               "assigned = " + nt.isAssigned() + "; " +
               "map = " + nt.isMap);
      mesosIdToMesosTask.remove(nt.mesosId);
      if (nt.hadoopId != null) {
        LOG.info("Assigned hadoop id " + nt.hadoopId);
        hadoopIdToMesosTask.remove(nt.hadoopId);
      }
      TaskTrackerInfo ttInfo = ttInfos.get(nt.host);
      if (ttInfo != null) {
        ttInfo.remove(nt);
      }
      if (nt.isMap) {
        if (nt.isAssigned())
          assignedMaps--;
        else
          unassignedMaps--;
      } else {
        if (nt.isAssigned())
          assignedReduces--;
        else
          unassignedReduces--;
      }
    }
  }

  private void askExecutorToUpdateStatus(MesosTask nt, TaskState state) {
    TaskTrackerInfo ttInfo = ttInfos.get(nt.host);
    if (ttInfo != null) {
      HadoopFrameworkMessage message = new HadoopFrameworkMessage(
          HadoopFrameworkMessage.Type.S2E_SEND_STATUS_UPDATE,
          state.toString(),
          nt.mesosId.getValue());
      try {
        LOG.info("Asking slave " + ttInfo.mesosSlaveId.getValue() +
                 " to update status for task " + nt.mesosId.getValue() +
                 " to " + state);
        driver.sendFrameworkMessage(
            EXECUTOR_ID, ttInfo.mesosSlaveId, message.serialize());
      } catch (IOException e) {
        // This exception would only get thrown if we couldn't
        // serialize the HadoopFrameworkMessage, which is a serious
        // problem; crash the JT.
        throw new RuntimeException(
            "Failed to serialize HadoopFrameworkMessage", e);
      }
    }
  }

  private void killExcessTasks(JobInProgress job) {
    LOG.info("Need to run more maps for " + job.getJobID());
    int numToKill = Math.max(1, 
        Math.min(job.pendingMaps(), job.runningReduceTasks / 2));
    LOG.info("Killing " + numToKill +
        " reduce tasks to open map slots");
    List<TaskInProgress> reduces = new ArrayList<TaskInProgress>();
    reduces.addAll(job.runningReduces);
    // TODO (Charles): Account for speculative tasks.
    Collections.sort(reduces,
        new java.util.Comparator<TaskInProgress>() {
          public final int compare(TaskInProgress a, TaskInProgress b) {
            return Double.compare(b.getProgress(), a.getProgress());
          }
        });
    for (int i = 0; i < numToKill; ++i) {
      TaskInProgress tip = reduces.get(i);
      for (TaskAttemptID id: tip.getAllTaskAttemptIDs()) {
        if (tip.isRunningTask(id)) {
          if (tip.killTask(id, false)) {
            break;
          } else {
            LOG.info("Failed to kill task attempt " + id);
          }
        }
      }
    }
  }

  // Check to see if we're only running reduce tasks but can run non-backup
  // map tasks for the same job.
  //
  // TOOD(Charles): Ideally, we'd check this on reduce fetch failures.
  private void killExcessTasks() {
    synchronized (jobTracker)  {
      Collection<JobInProgress> jobs = jobTracker.jobs.values();
      int numMaps = 0;
      for (JobInProgress job : jobs) {
        numMaps += job.runningMapTasks;
      }
      if (numMaps == 0) {
        LOG.info("Not running any maps; checking if reducers are stalled");
        for (JobInProgress job : jobs) {
          if (job.runningReduceTasks > 0 && job.pendingMaps() > 0) {
            killExcessTasks(job);
          }
        }
      }
    }
  }

  // Kill any unlaunched tasks that have timed out
  public void killTimedOutTasks() {
    synchronized (jobTracker) {
      long curTime = System.currentTimeMillis();
      long timeout = 20000;
      long minCreationTime = curTime - timeout;
      LOG.info("Killing tasks that started " + timeout + " milliseconds ago");
      for (TaskTrackerInfo tt: ttInfos.values()) {
        killTimedOutTasks(tt.maps, minCreationTime);
        killTimedOutTasks(tt.reduces, minCreationTime);
      }
      killExcessTasks();
      driver.reviveOffers();
    }
  }

  private void killTimedOutTasks(List<MesosTask> tasks, long minCreationTime) {
    synchronized (jobTracker) {
      List<MesosTask> toRemove = new ArrayList<MesosTask>();
      for (MesosTask nt: tasks) {
        if (!nt.isAssigned() && nt.creationTime < minCreationTime) {
          toRemove.add(nt);
        }
      }
      for (MesosTask nt: toRemove) {
        LOG.info("Killing timedout task " + nt.mesosId.getValue() +
                 " created at " + nt.creationTime);
        askExecutorToUpdateStatus(nt, TaskState.TASK_KILLED);
      }
    }
  }

  @Override
  public void frameworkMessage(SchedulerDriver d, ExecutorID eId, SlaveID sId, byte[] message) {
    // TODO: Respond to E2S_KILL_REQUEST message by killing a task
    LOG.info("Unhandled frameworkMessage");
  }

  @Override
  public void slaveLost(SchedulerDriver d, SlaveID slaveId) {}

  @Override
  public void executorLost(SchedulerDriver d,
                           ExecutorID executorId,
                           SlaveID slaveId,
                           int status) {}

  public void error(SchedulerDriver d, String message) {
    LOG.error("FrameworkScheduler.error: " + message);
  }

  @Override
  public void offerRescinded(SchedulerDriver d, OfferID oId) {}

  // Methods to check whether a job has runnable tasks

  /**
   * Check whether the job can launch a map task on a given node, with a given
   * level of locality (maximum cache level). Also includes job setup and
   * cleanup tasks, as well as map cleanup tasks. Returns the locality level of
   * the launchable map if one exists, or -1 otherwise.
   *
   * This is currently fairly long because it replicates a lot of the logic
   * in findNewMapTask. Unfortunately, it's not easy to just use findNewMapTask
   * directly, because that requires a TaskTracker. One way to avoid requiring
   * this method would be to just launch TaskTrackers on every node, without
   * first checking for locality.
   */
  int availableMapLevel(JobInProgress job, String host, int maxCacheLevel) {
    synchronized (job) {
      // For scheduling a map task, we have two caches and a list (optional)
      //  I)   one for non-running task
      //  II)  one for running task (this is for handling speculation)
      //  III) a list of TIPs that have empty locations (e.g., dummy splits),
      //       the list is empty if all TIPs have associated locations

      // First a look up is done on the non-running cache and on a miss, a look
      // up is done on the running cache. The order for lookup within the cache:
      //   1. from local node to root [bottom up]
      //   2. breadth wise for all the parent nodes at max level

      //if (canLaunchJobCleanupTask()) return true;
      //if (canLaunchSetupTask()) return true;
      if (!job.mapCleanupTasks.isEmpty()) return 0;

      // Return false right away if the task cache isn't ready, either because
      // we are still initializing or because we are cleaning up
      if (job.nonRunningMapCache == null) {
        LOG.info("availableMapLevel for " + job.getJobID() + ": map cache not ready");
        return -1;
      }

      // We fall to linear scan of the list (III above) if we have misses in the
      // above caches

      Node node = jobTracker.getNode(host);

      int maxLevel = job.getMaxCacheLevel();
      LOG.info("maxLevel = " + maxLevel);

      //
      // I) Non-running TIP :
      //

      // 1. check from local node to the root [bottom up cache lookup]
      //    i.e if the cache is available and the host has been resolved
      //    (node!=null)
      if (node != null) {
        Node key = node;
        int level = 0;
        // maxCacheLevel might be greater than this.maxLevel if findNewMapTask is
        // called to schedule any task (local, rack-local, off-switch or speculative)
        // tasks or it might be NON_LOCAL_CACHE_LEVEL (i.e. -1) if findNewMapTask is
        //  (i.e. -1) if findNewMapTask is to only schedule off-switch/speculative
        // tasks
        int maxLevelToSchedule = Math.min(maxCacheLevel, maxLevel);
        for (level = 0;level < maxLevelToSchedule; ++level) {
          LOG.info("Checking locally at level " + level + "; key = " + key.getName());
          List <TaskInProgress> cacheForLevel = job.nonRunningMapCache.get(key);
          if (hasUnlaunchedTask(cacheForLevel)) {
            return level;
          }
          key = key.getParent();
        }

        // Check if we need to only schedule a local task (node-local/rack-local)
        if (level == maxCacheLevel) {
          LOG.info("availableMapLevel: rejecting for maxCacheLevel");
          return -1;
        }
      } else {
        LOG.info("No local node for " + host);
      }

      //2. Search breadth-wise across parents at max level for non-running
      //   TIP if
      //     - cache exists and there is a cache miss
      //     - node information for the tracker is missing (tracker's topology
      //       info not obtained yet)

      // collection of node at max level in the cache structure
      Collection<Node> nodesAtMaxLevel = jobTracker.getNodesAtMaxLevel();

      // get the node parent at max level
      Node nodeParentAtMaxLevel =
        (node == null) ? null : JobTracker.getParentNode(node, maxLevel - 1);

      for (Node parent : nodesAtMaxLevel) {

        // skip the parent that has already been scanned
        if (parent == nodeParentAtMaxLevel) {
          LOG.info("Skipping checking " + parent.getName());
          continue;
        }

        LOG.info("Checking " + parent.getName());

        List<TaskInProgress> cache = job.nonRunningMapCache.get(parent);
        if (hasUnlaunchedTask(cache)) {
          LOG.info("nonRunningMapCache: has unlaunched task at level " + maxLevel);
          return maxLevel-1;
        }
      }

      // 3. Search non-local tips for a new task
      if (hasUnlaunchedTask(job.nonLocalMaps))
        return 0;

      //
      // II) Running TIP :
      //

      if (job.getMapSpeculativeExecution()) {
        long time = System.currentTimeMillis();
        float avgProg = job.status.mapProgress();

        // 1. Check bottom up for speculative tasks from the running cache
        if (node != null) {
          Node key = node;
          for (int level = 0; level < maxLevel; ++level) {
            Set<TaskInProgress> cacheForLevel = job.runningMapCache.get(key);
            if (cacheForLevel != null) {
              for (TaskInProgress tip: cacheForLevel) {
                if (tip.isRunning() && tip.hasSpeculativeTask(time, avgProg)) {
                  return level;
                }
              }
            }
            key = key.getParent();
          }
        }

        // 2. Check breadth-wise for speculative tasks

        for (Node parent : nodesAtMaxLevel) {
          // ignore the parent which is already scanned
          if (parent == nodeParentAtMaxLevel) {
            continue;
          }

          Set<TaskInProgress> cache = job.runningMapCache.get(parent);
          if (cache != null) {
            for (TaskInProgress tip: cache) {
              if (tip.isRunning() && tip.hasSpeculativeTask(time, avgProg)) {
                return maxLevel-1;
              }
            }
          }
        }

        // 3. Check non-local tips for speculation
        for (TaskInProgress tip: job.nonLocalRunningMaps) {
          if (tip.isRunning() && tip.hasSpeculativeTask(time, avgProg)) {
            return 0;
          }
        }
      }

      LOG.info("Defaulting to no task found");
      return -1;
    }
  }

  /**
   * Check whether a task list (from the non-running map cache) contains any
   * unlaunched tasks.
   */
  boolean hasUnlaunchedTask(Collection<TaskInProgress> cache) {
    if (cache != null)
      for (TaskInProgress tip: cache) {
        LOG.info("Checking " + tip.getTIPId() + ": " + 
                 tip.isRunnable() + " / " + tip.isRunning());
        if (!tip.isRunning()) {
          LOG.info("Checking if we can use " + tip.getTIPId());
          for (TaskStatus status : tip.getTaskStatuses()) {
            LOG.info("Status " + status.getTaskID() + ": " +
                status.getRunState());
          }
        }
        if (tip.isRunnable() && !tip.isRunning())
          return true;
      }
    return false;
  }

  /**
   * Check whether a job can launch a reduce task. Also includes reduce
   * cleanup tasks.
   *
   * As with hasMapToLaunch, this duplicates the logic inside
   * findNewReduceTask. Please see the comment there for an explanation.
   */
  boolean hasReduceToLaunch(JobInProgress job) {
    synchronized (job) {
      // Return false if not enough maps have finished to launch reduces
      if (!job.scheduleReduces()) return false;

      // Check for a reduce cleanup task
      if (!job.reduceCleanupTasks.isEmpty()) return true;

      // Return false right away if the task cache isn't ready, either because
      // we are still initializing or because we are cleaning up
      if (job.nonRunningReduces == null) return false;

      // Check for an unlaunched reduce
      if (job.nonRunningReduces.size() > 0) return true;

      // Check for a reduce to be speculated
      if (job.getReduceSpeculativeExecution()) {
        long time = System.currentTimeMillis();
        float avgProg = job.status.reduceProgress();
        for (TaskInProgress tip: job.runningReduces) {
          if (tip.isRunning() && tip.hasSpeculativeTask(time, avgProg)) {
            return true;
          }
        }
      }

      return false;
    }
  }
}
