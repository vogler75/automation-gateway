import React, { useEffect, useState } from "react";
import { usePageTitle } from "../utils/PageTitleContext";
import { FileSearchOutlined, MenuOutlined } from "@ant-design/icons";
import {
  Button,
  Card,
  Col,
  Row,
  Dropdown,
  Menu,
  message,
  Space,
  Tooltip,
  ConfigProvider,
} from "antd";
import { useAuth } from "../utils/auth";
import ShowMoreLogsPopup from "../components/ShowMoreLogsPopup";

const handleMenuClick = async (key, id, type, auth, fetchData) => {
  const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
  const serverURL = `http://${endpoint}`;
  let mutation = "";

  if (key === "enable") {
    mutation = `
      mutation {
        enableComponent(type: ${type}, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "disable") {
    mutation = `
      mutation {
        disableComponent(type: ${type}, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "delete") {
    mutation = `
      mutation {
        deleteComponent(type: ${type}, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  }

  if (mutation) {
    try {
      const response = await fetch(`${serverURL}/admin/api`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: auth.token.replace(/["']/g, ""),
        },
        body: JSON.stringify({ query: mutation }),
      });

      const data = await response.json();
      if (data.errors) {
        message.error(data.errors[0].message);
      } else {
        message.success(
          `${key.charAt(0).toUpperCase() + key.slice(1)} operation successful!`
        );
        await fetchData();
      }
    } catch (error) {
      console.error("Error sending mutation to API:", error);
      message.error("Operation failed!");
    }
  }
};

export function Dashboard() {
  const [isPopupVisible, setIsPopupVisible] = useState(false);
  const [selectedItem, setSelectedItem] = useState(null);
  const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
  const serverURL = `http://${endpoint}`;
  const { setTitle } = usePageTitle();
  const auth = useAuth();
  const [drivers, setDrivers] = useState([]);
  const [servers, setServers] = useState([]);
  const [loggers, setLoggers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState({
    showDrivers: true,
    showServers: true,
    showLoggers: true,
  });

  const fetchData = async () => {
    const queries = [
      `{ getComponents(group: Driver) { id type status config } }`,
      `{ getComponents(group: Server) { id type status config } }`,
      `{ getComponents(group: Logger) { id type status config } }`,
    ];

    try {
      const fetchResponses = await Promise.all(
        queries.map((query) =>
          fetch(`${serverURL}/admin/api`, {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              Authorization: auth.token.replace(/["']/g, ""),
            },
            body: JSON.stringify({ query }),
          })
        )
      );

      const [driverData, serverData, loggerData] = await Promise.all(
        fetchResponses.map((response) => response.json())
      );

      setDrivers(driverData?.data?.getComponents || []);
      setServers(serverData?.data?.getComponents || []);
      setLoggers(loggerData?.data?.getComponents || []);
    } catch (error) {
      console.error("Error fetching data from API:", error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setTitle("Dashboard");
    fetchData();
  }, []);

  const renderCard = (item, bgColor) => (
    <Col style={{ margin: "8px" }} key={item.id}>
      <Card
        title={
          item.id.length > 20 ? (
            <Tooltip title={item.id}>
              <div className="card-title">
                {item.id.substring(0, 20) + "..."}
              </div>
            </Tooltip>
          ) : (
            <div className="card-title">{item.id}</div>
          )
        }
        bordered={false}
        extra={
          <Dropdown
            overlay={
              <Menu>
                <Menu.Item
                  key="enable"
                  onClick={() =>
                    handleMenuClick(
                      "enable",
                      item.id,
                      item.type,
                      auth,
                      fetchData
                    )
                  }
                >
                  Enable
                </Menu.Item>
                <Menu.Item
                  key="disable"
                  onClick={() =>
                    handleMenuClick(
                      "disable",
                      item.id,
                      item.type,
                      auth,
                      fetchData
                    )
                  }
                >
                  Disable
                </Menu.Item>
                <Menu.Item
                  key="delete"
                  onClick={() =>
                    handleMenuClick(
                      "delete",
                      item.id,
                      item.type,
                      auth,
                      fetchData
                    )
                  }
                  danger
                >
                  Delete
                </Menu.Item>
              </Menu>
            }
            trigger={["click"]}
          >
            <Button className="btn-action-custom">
              <MenuOutlined />
            </Button>
          </Dropdown>
        }
        style={{ backgroundColor: bgColor, width: "225px" }}
      >
        <p>
          <strong>Type:</strong> {item.type}
        </p>
        <p>
          <strong>Status:</strong> {item.status}
        </p>
        <p>
          <strong>Log Level:</strong> {JSON.parse(item.config).LogLevel}
        </p>
        <div
          style={{
            display: "flex",
            justifyContent: "flex-end",
            width: "10px",
          }}
        >
          <Button
            onClick={() => handleShowPopup(item)}
            className="custom-button"
            style={{
              background: "transparent",
              border: "none",
              padding: 0,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              boxShadow: "none",
              outline: "none",
            }}
          >
            <FileSearchOutlined />
          </Button>
        </div>
        {isPopupVisible && selectedItem && (
          <ShowMoreLogsPopup
            visible={isPopupVisible}
            onClose={handleClosePopup}
            type={selectedItem.type}
            id={selectedItem.id}
          />
        )}
      </Card>
    </Col>
  );

  const toggleFilter = (type) => {
    setFilter((prevFilter) => ({
      ...prevFilter,
      [type]: !prevFilter[type],
    }));
  };

  const getSquareStyle = (active, color) => ({
    width: 12,
    height: 12,
    marginRight: 8,
    backgroundColor: active ? color : "#555555",
    display: "inline-block",
  });

  const filterTextStyle = {
    cursor: "pointer",
    color: "black",
    fontWeight: "normal",
  };

  const handleShowPopup = (item) => {
    setSelectedItem(item);
    setIsPopupVisible(true);
  };

  const handleClosePopup = () => {
    setIsPopupVisible(false);
    setSelectedItem(null);
  };

  return (
    <>
      <p></p>
      <Space style={{ marginBottom: "16px" }}>
        <div
          onClick={() => toggleFilter("showDrivers")}
          style={filterTextStyle}
          onMouseOver={(e) => {
            if (filter.showDrivers)
              e.target.style.textDecoration = "line-through";
          }}
          onMouseOut={(e) => {
            e.target.style.textDecoration = "none";
          }}
        >
          <span style={getSquareStyle(filter.showDrivers, "#d5f5d5")}></span>
          Drivers
        </div>
        <div
          onClick={() => toggleFilter("showServers")}
          style={filterTextStyle}
          onMouseOver={(e) => {
            if (filter.showServers)
              e.target.style.textDecoration = "line-through";
          }}
          onMouseOut={(e) => {
            e.target.style.textDecoration = "none";
          }}
        >
          <span style={getSquareStyle(filter.showServers, "#ffebcc")}></span>
          Servers
        </div>
        <div
          onClick={() => toggleFilter("showLoggers")}
          style={filterTextStyle}
          onMouseOver={(e) => {
            if (filter.showLoggers)
              e.target.style.textDecoration = "line-through";
          }}
          onMouseOut={(e) => {
            e.target.style.textDecoration = "none";
          }}
        >
          <span style={getSquareStyle(filter.showLoggers, "#cceeff")}></span>
          Loggers
        </div>
      </Space>

      {loading ? (
        <p>Loading data...</p>
      ) : (
        <Row
          style={{
            display: "flex",
            flexWrap: "wrap",
            justifyContent: "start",
            gap: "0",
          }}
        >
          {filter.showDrivers &&
            drivers.map((item) => renderCard(item, "#d5f5d5"))}
          {filter.showServers &&
            servers.map((item) => renderCard(item, "#ffebcc"))}
          {filter.showLoggers &&
            loggers.map((item) => renderCard(item, "#cceeff"))}
        </Row>
      )}
    </>
  );
}
