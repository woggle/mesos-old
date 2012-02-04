#include <gmock/gmock.h>

#include "fake/scenario.hpp"
#include "fake/fake_task_simple.hpp"

using namespace mesos;
using namespace mesos::internal;
using namespace mesos::internal::fake;

class FakeScenarioTest : public testing::Test
{
protected:
  void makeBatchTask(int i, std::map<TaskID, FakeTask*>* tasks) {
    TaskID id;
    id.set_value(std::string("task") + boost::lexical_cast<std::string>(i));
    (*tasks)[id] = new BatchTask(Resources::parse("mem:512"),
                                 ResourceHints::parse("mem:512;cpus:1",
                                                      ""),
                                 30.0, 3.0);
  }

  void batchTest(int numSlaves, int numTasks, double time) {
    process::Clock::pause();
    scenario.spawnMaster();
    for (int i = 0; i < numSlaves; ++i) {
      scenario.spawnSlave(Resources::parse("cpus:4;mem:1024"));
    }
    std::map<TaskID, FakeTask*> tasks;
    for (int i = 0; i < numTasks; ++i) {
      makeBatchTask(i, &tasks);
    }
    scenario.spawnScheduler("batch", tasks);
    scenario.finishSetup();
    scenario.runFor(time);
    EXPECT_EQ(0, scenario.getScheduler("batch")->countPending());
    EXPECT_EQ(0, scenario.getScheduler("batch")->countRunning());
    scenario.stop();
    process::Clock::resume();
  }
  Scenario scenario;
};

// These tests assume that the isolation policy prevents tasks from getting
// "free" CPU cycles.
TEST_F(FakeScenarioTest, OneTaskBatch)
{
  batchTest(1, 1, 30.1);
}

TEST_F(FakeScenarioTest, OneTaskTwoSlavesBatch)
{
  batchTest(2, 1, 30.1);
}

TEST_F(FakeScenarioTest, TwoTasksOneSlaveBatch)
{
  batchTest(1, 2, 30.1);
}

TEST_F(FakeScenarioTest, ThreeTasksOneSlaveBatch)
{
  batchTest(1, 3, 60.1);
}

TEST_F(FakeScenarioTest, ThreeTasksTwoSlavesBatch)
{
  batchTest(2, 3, 30.1);
}

TEST_F(FakeScenarioTest, FiveTasksTwoSlavesBatch)
{
  batchTest(2, 3, 60.1);
}
