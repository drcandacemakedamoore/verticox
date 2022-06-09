import logging
from logging.handlers import RotatingFileHandler
from multiprocessing import Process

from verticox.grpc.datanode_pb2 import Empty

logging.basicConfig(level=logging.DEBUG)
import pandas as pd
from concurrent.futures import ThreadPoolExecutor

import grpc
from sksurv.datasets import load_whas500

from verticox.aggregator import Aggregator
from verticox.datanode import DataNode
from verticox.grpc.datanode_pb2_grpc import add_DataNodeServicer_to_server, DataNodeStub

_logger = logging.getLogger(__name__)

MAX_WORKERS = 5
PORT1 = 7777
PORT2 = 7779
GRPC_OPTIONS = [('wait_for_ready', True)]
DATA_LIMIT = 5


def get_test_dataset(limit=None):
    features, events = load_whas500()

    if limit:
        features = features.head(limit)
        events = events[:DATA_LIMIT]

    features = features.values.astype(float)

    return features, events


def run_datanode_grpc_server(features, event_times, right_censored, port, name):
    server = grpc.server(ThreadPoolExecutor(),
                         options=GRPC_OPTIONS)
    add_DataNodeServicer_to_server(DataNode(features=features, event_times=event_times,
                                            right_censored=right_censored, name=name), server)
    server.add_insecure_port(f'[::]:{port}')
    _logger.info(f'Starting datanode on port {port}')
    server.start()
    server.wait_for_termination()


def split_events(events):
    df = pd.DataFrame(events)
    times = df.lenfol.values
    right_censored = df.fstat.values

    return times, right_censored


def test_integration():
    _logger.addHandler(RotatingFileHandler('log.txt'))

    features, events = get_test_dataset(limit=DATA_LIMIT)
    num_features = features.shape[1]
    feature_split = num_features // 2
    features1 = features[:, :feature_split]
    features2 = features[:, feature_split:]
    event_times, right_censored = split_events(events)

    p1 = Process(target=run_datanode, args=(event_times, features1, right_censored, PORT1, 'first'))
    p2 = Process(target=run_datanode, args=(event_times, features2, right_censored, PORT2,
                                            'second'))

    p1.start()
    p2.start()

    stub1 = get_datanode_client(PORT1)
    stub2 = get_datanode_client(PORT2)

    print(stub1.getBeta(Empty()))
    print(stub2.getBeta(Empty()))

    # institutions = [stub1, stub2]

    # logging.info(f'Initializing aggregator connected to {len(institutions)} institutions')
    # aggregator = Aggregator(institutions, event_times, right_censored)

    aggregator_process = Process(target=run_aggregator,
                                 args=([PORT1, PORT2], event_times, right_censored))

    _logger.info('Starting aggregator')
    aggregator_process.start()
    aggregator_process.join()
    p1.kill()
    p2.kill()


def run_aggregator(ports, event_times, right_censored):
    stubs = [get_datanode_client(port) for port in ports]

    aggregator = Aggregator(stubs, event_times, right_censored)

    aggregator.fit()

    _logger.info(f'Resulting betas: {aggregator.get_betas()}')


def run_datanode(event_times, features, right_censored, port, name):
    run_datanode_grpc_server(features, event_times, right_censored, port, name)


def get_datanode_client(port):
    logging.info(f'Connecting to datanode at port {port}')
    channel = grpc.insecure_channel(f'localhost:{port}', options=GRPC_OPTIONS)
    # ready = grpc.channel_ready_future(channel)
    # ready.result(timeout=300)
    stub = DataNodeStub(channel)
    return stub


if __name__ == '__main__':
    test_integration()
