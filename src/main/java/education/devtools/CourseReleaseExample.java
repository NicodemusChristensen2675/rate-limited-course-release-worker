package education.devtools;

import education.devtools.InfraiQueueClient.BuildEvent;
import education.devtools.ReleaseQueueWorker.ReleaseDiagnostic;

public final class CourseReleaseExample {
    private CourseReleaseExample() {}

    public static void main(String[] args) throws Exception {
        WorkerConfiguration configuration = WorkerConfiguration.load();
        InfraiQueueClient queue = new InfraiQueueClient(configuration);
        BuildEvent lessonBuild = new BuildEvent(
                "java-concurrency-101", "8c4f0d2", "https://artifacts.example.edu/java-concurrency-101/8c4f0d2");

        queue.publish(lessonBuild);
        ReleaseQueueWorker worker = new ReleaseQueueWorker(queue, configuration, event ->
                new ReleaseDiagnostic(event.courseId(), event.commitSha(), "released for instructor review"));

        for (ReleaseDiagnostic diagnostic : worker.runOnce()) {
            System.out.printf("course=%s commit=%s outcome=%s%n",
                    diagnostic.courseId(), diagnostic.commitSha(), diagnostic.outcome());
        }
    }
}
