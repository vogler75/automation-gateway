import React, { useState, useEffect } from "react";
import { Modal, Table, Button, Input, ConfigProvider, Tree } from "antd";
import { LoadingOutlined } from "@ant-design/icons";
import { useAuth } from "../utils/auth";

const ShowMoreLogsPopup = ({ visible, onClose, type, id }) => {
  const auth = useAuth();
  const [logs, setLogs] = useState([]);
  const [filteredData, setFilteredData] = useState([]);
  const [filters, setFilters] = useState({
    time: null,
    level: null,
    message: null,
  });
  const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
  const serverURL = `http://${endpoint}`;

  const fetchLogs = async () => {
    const query = `{
      getMessages(type: ${type}, id: "${id}", last: 50) {
        time
        level
        name
        message
      }
    }`;

    try {
      const response = await fetch(`${serverURL}/admin/api`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: auth.token.replace(/["']/g, ""),
        },
        body: JSON.stringify({ query }),
      });

      const result = await response.json();
      console.log("API response:", result);

      const messages = result?.data?.getMessages || [];
      setLogs(messages);
      setFilteredData(messages);
    } catch (error) {
      console.error("Error fetching result from API:", error);
    }
  };

  useEffect(() => {
    let intervalId;

    if (visible) {
      fetchLogs();
      intervalId = setInterval(fetchLogs, 5000);
    }

    return () => {
      clearInterval(intervalId);
    };
  }, [visible]);

  const applyFilters = (data) => {
    return data.filter((log) => {
      return (
        (!filters.time || log.time.includes(filters.time)) &&
        (!filters.level || log.level.includes(filters.level)) &&
        (!filters.message || log.message.includes(filters.message))
      );
    });
  };

  const handleFilterClear = (key, confirm) => {
    setFilters((prevFilters) => ({
      ...prevFilters,
      [key]: null,
    }));
    confirm();
  };

  const onTreeSelect = (selectedKeys) => {
    setFilters((prevFilters) => ({
      ...prevFilters,
      level: selectedKeys[0], // Select the level from the tree
    }));
  };

  const columns = [
    {
      title: "Timestamp",
      dataIndex: "time",
      key: "time",
      render: (text) => new Date(text).toLocaleString(),
      sorter: (a, b) => new Date(a.time) - new Date(b.time),
      width: 200,
      filteredValue: filters.time ? [filters.time] : null,
      filterDropdown: ({ setSelectedKeys, selectedKeys, confirm }) => (
        <div style={{ padding: 8 }}>
          <Input
            placeholder="Search Timestamps"
            value={selectedKeys[0]}
            onChange={(e) => {
              setSelectedKeys([e.target.value]);
              setFilters((prevFilters) => ({
                ...prevFilters,
                time: e.target.value,
              }));
              confirm({ closeDropdown: false });
            }}
            style={{ width: 188, marginBottom: 8, display: "block" }}
          />
          <Button
            onClick={() => handleFilterClear("time", confirm)}
            size="small"
            style={{ width: 100 }}
            disabled={!filters.time}
            className="custom-button-hover"
          >
            Remove Filter
          </Button>
        </div>
      ),
    },
    {
      title: "Level",
      dataIndex: "level",
      key: "level",
      sorter: (a, b) => a.level.localeCompare(b.level),
      width: 100,
      filteredValue: filters.level ? [filters.level] : null,
      filterDropdown: ({ confirm }) => (
        <div style={{ padding: 8 }}>
          <Tree
            treeData={[
              {
                title: "INFO",
                key: "INFO",
              },
              {
                title: "ALL",
                key: "ALL",
              },
              {
                title: "SEVERE",
                key: "SEVERE",
              },
              {
                title: "WARNING",
                key: "WARNING",
              },
              {
                title: "CONFIG",
                key: "CONFIG",
              },
              {
                title: "FINE",
                key: "FINE",
              },
              {
                title: "FINER",
                key: "FINER",
              },
              {
                title: "FINEST",
                key: "FINEST",
              },
              {
                title: "OFF",
                key: "OFF",
              },
            ]}
            defaultExpandedKeys={[
              "INFO",
              "ALL",
              "SEVERE",
              "WARNING",
              "CONFIG",
              "FINE",
              "FINER",
              "FINEST",
              "OFF",
            ]}
            onSelect={(selectedKeys) => onTreeSelect(selectedKeys)}
            selectedKeys={filters.level ? [filters.level] : []}
            style={{ width: 188, marginBottom: 8 }}
          />
          <Button
            onClick={() => handleFilterClear("level", confirm)}
            size="small"
            style={{ width: 100 }}
            disabled={!filters.level}
            className="custom-button-hover"
          >
            Remove Filter
          </Button>
        </div>
      ),
    },
    {
      title: "Message",
      dataIndex: "message",
      key: "message",
      sorter: (a, b) => a.message.localeCompare(b.message),
      width: 400,
      filteredValue: filters.message ? [filters.message] : null,
      filterDropdown: ({ setSelectedKeys, selectedKeys, confirm }) => (
        <div style={{ padding: 8 }}>
          <Input
            placeholder="Search Message"
            value={selectedKeys[0]}
            onChange={(e) => {
              setSelectedKeys([e.target.value]);
              setFilters((prevFilters) => ({
                ...prevFilters,
                message: e.target.value,
              }));
              confirm({ closeDropdown: false });
            }}
            style={{ width: 188, marginBottom: 8, display: "block" }}
          />
          <Button
            onClick={() => handleFilterClear("message", confirm)}
            size="small"
            style={{ width: 100 }}
            disabled={!filters.message}
            className="custom-button-hover"
          >
            Remove Filter
          </Button>
        </div>
      ),
    },
  ];

  return (
    <>
      {visible && (
        <Modal
          title={
            <span>
              <LoadingOutlined spin /> Logs for {id}
            </span>
          }
          open={visible}
          onCancel={onClose}
          footer={null}
          width={800}
        >
          <ConfigProvider
            theme={{
              components: {
                Table: {
                  rowSelectedHoverBg: "#000000",
                },
              },
            }}
          >
            <Table
              dataSource={applyFilters(filteredData)}
              columns={columns}
              rowKey="time"
              pagination={false}
              scroll={{ y: 300 }}
              bordered
            />
          </ConfigProvider>
        </Modal>
      )}
    </>
  );
};

export default ShowMoreLogsPopup;
