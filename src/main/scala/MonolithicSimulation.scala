package ClusterSchedulingSimulation

import scala.collection.mutable.HashMap

/* This class and its subclasses are used by factory method
 * ClusterSimulator.newScheduler() to determine which type of Simulator
 * to create and also to carry any extra fields that the factory needs to
 * construct the simulator.
 */
class MonolithicSimulatorDesc(schedulerDescs: Seq[SchedulerDesc],
                              runTime: Double)
                             extends ClusterSimulatorDesc(runTime){
  override
  def newSimulator(constantThinkTime: Double,
                   perTaskThinkTime: Double,
                   blackListPercent: Double,
                   schedulerWorkloadsToSweepOver: Map[String, Seq[String]],
                   workloadToSchedulerMap: Map[String, Seq[String]],
                   cellStateDesc: CellStateDesc,
                   workloads: Seq[Workload],
                   prefillWorkloads: Seq[Workload],
                   logging: Boolean = false): ClusterSimulator = {
    var schedulers = HashMap[String, Scheduler]()
    // Create schedulers according to experiment parameters.
    schedulerDescs.foreach(schedDesc => {
      // If any of the scheduler-workload pairs we're sweeping over
      // are for this scheduler, then apply them before
      // registering it.
      var constantThinkTimes = HashMap[String, Double](
          schedDesc.constantThinkTimes.toSeq: _*)
      var perTaskThinkTimes = HashMap[String, Double](
          schedDesc.perTaskThinkTimes.toSeq: _*)
      var newBlackListPercent = 0.0
      if (schedulerWorkloadsToSweepOver
          .contains(schedDesc.name)) {
        newBlackListPercent = blackListPercent
        schedulerWorkloadsToSweepOver(schedDesc.name)
            .foreach(workloadName => {
          constantThinkTimes(workloadName) = constantThinkTime
          perTaskThinkTimes(workloadName) = perTaskThinkTime
        })
      }
      schedulers(schedDesc.name) =
          new MonolithicScheduler(schedDesc.name,
                                  constantThinkTimes.toMap,
                                  perTaskThinkTimes.toMap,
                                  math.floor(newBlackListPercent *
                                    cellStateDesc.numMachines.toDouble).toInt)
    })

    val cellState = new CellState(cellStateDesc.numMachines,
                                  cellStateDesc.cpusPerMachine,
                                  cellStateDesc.memPerMachine,
                                  conflictMode = "resource-fit",
                                  transactionMode = "all-or-nothing")

    new ClusterSimulator(cellState,
                         schedulers.toMap,
                         workloadToSchedulerMap,
                         workloads,
                         prefillWorkloads,
                         logging)
  }
}

class MonolithicScheduler(name: String,
                          constantThinkTimes: Map[String, Double],
                          perTaskThinkTimes: Map[String, Double],
                          numMachinesToBlackList: Double = 0)
                         extends Scheduler(name,
                                           constantThinkTimes,
                                           perTaskThinkTimes,
                                           numMachinesToBlackList) {

  println("scheduler-id-info: %d, %s, %d, %s, %s"
          .format(Thread.currentThread().getId(),
                  name,
                  hashCode(),
                  constantThinkTimes.mkString(";"),
                  perTaskThinkTimes.mkString(";")))

  override
  def addJob(job: Job) = {
    assert(simulator != null, "This scheduler has not been added to a " +
                              "simulator yet.")
    super.addJob(job)
    job.lastEnqueued = simulator.currentTime
    pendingQueue.enqueue(job)
    simulator.log("enqueued job " + job.id)
    if (!scheduling)
      scheduleNextJobAction()
  }

  /**
   * Checks to see if there is currently a job in this scheduler's job queue.
   * If there is, and this scheduler is not currently scheduling a job, then
   * pop that job off of the queue and "begin scheduling it". Scheduling a
   * job consists of setting this scheduler's state to scheduling = true, and
   * adding a finishSchedulingJobAction to the simulators event queue by
   * calling afterDelay().
   */
  def scheduleNextJobAction(): Unit = {
    assert(simulator != null, "This scheduler has not been added to a " +
                              "simulator yet.")
    if (!scheduling && !pendingQueue.isEmpty) {
      scheduling = true
      val job = pendingQueue.dequeue
      job.updateTimeInQueueStats(simulator.currentTime)
      job.lastSchedulingStartTime = simulator.currentTime
      val thinkTime = getThinkTime(job)
      simulator.log("getThinkTime returned " + thinkTime)
      simulator.afterDelay(thinkTime) {
        simulator.log(("Scheduler %s finished scheduling job %d. " +
                       "Attempting to schedule next job in scheduler's " +
                       "pendingQueue.").format(name, job.id))
        job.numSchedulingAttempts += 1
        job.numTaskSchedulingAttempts += job.unscheduledTasks
        val claimDeltas = scheduleJob(job, simulator.cellState)
        if(claimDeltas.length > 0) {
          simulator.cellState.scheduleEndEvents(claimDeltas)
          job.unscheduledTasks -= claimDeltas.length
          simulator.log("scheduled %d tasks of job %d's, %d remaining."
                        .format(claimDeltas.length, job.id, job.unscheduledTasks))
          numSuccessfulTransactions += 1
          recordUsefulTimeScheduling(job,
                                     thinkTime,
                                     job.numSchedulingAttempts == 1)
        } else {
          simulator.log(("No tasks scheduled for job %d (%f cpu %f mem) " +
                         "during this scheduling attempt, not recording " +
                         "any busy time. %d unscheduled tasks remaining.")
                        .format(job.id,
                                job.cpusPerTask,
                                job.memPerTask,
                                job.unscheduledTasks))
        }
        var jobEventType = "" // Set this conditionally below; used in logging.
        // If the job isn't yet fully scheduled, put it back in the queue.
        if (job.unscheduledTasks > 0) {
          simulator.log(("Job %s didn't fully schedule, %d / %d tasks remain " +
                         "(shape: %f cpus, %f mem). Putting it " +
                         "back in the queue").format(job.id,
                                                     job.unscheduledTasks,
                                                     job.numTasks,
                                                     job.cpusPerTask,
                                                     job.memPerTask))
          // Give up on a job if (a) it hasn't scheduled a single task in
          // 100 tries or (b) it hasn't finished scheduling after 1000 tries.
          if ((job.numSchedulingAttempts > 100 &&
               job.unscheduledTasks == job.numTasks) ||
              job.numSchedulingAttempts > 1000) {
            println(("Abandoning job %d (%f cpu %f mem) with %d/%d " +
                   "remaining tasks, after %d scheduling " +
                   "attempts.").format(job.id,
                                       job.cpusPerTask,
                                       job.memPerTask,
                                       job.unscheduledTasks,
                                       job.numTasks,
                                       job.numSchedulingAttempts))
            numJobsTimedOutScheduling += 1
            jobEventType = "abandoned"
          } else {
            simulator.afterDelay(1) {
              addJob(job)
            }
          }
        } else {
          // All tasks in job scheduled so don't put it back in pendingQueue.
          jobEventType = "fully-scheduled"
        }
        if (!jobEventType.equals("")) {
          // println("%s %s %d %s %d %d %f"
          //         .format(Thread.currentThread().getId(),
          //                 name,
          //                 hashCode(),
          //                 jobEventType,
          //                 job.id,
          //                 job.numSchedulingAttempts,
          //                 simulator.currentTime - job.submitted))
        }

        scheduling = false
        scheduleNextJobAction()
      }
      simulator.log("Scheduler named '%s' started scheduling job %d "
                    .format(name,job.id))
    }
  }
}
